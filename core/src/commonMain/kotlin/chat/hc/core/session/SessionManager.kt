package chat.hc.core.session

import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import chat.hc.core.protocol.ErrorId
import chat.hc.core.protocol.Outbound
import chat.hc.core.store.ChannelBuffer
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Everything the UI needs to render one channel. */
data class ChannelUi(
    val channel: String,
    val state: SessionState = SessionState.Idle,
    val messages: List<ChatMessage> = emptyList(),
    val roster: List<chat.hc.core.protocol.User> = emptyList(),
    val unread: Int = 0,
    /**
     * Closed by the user, but not yet actually left — see [SessionManager.beginLeave].
     * The socket is still up and the history is still here; the UI hides it and
     * offers to take it back.
     */
    val closing: Boolean = false,
)

/**
 * Owns every channel session, their buffers, and the single shared rate budget.
 *
 * This lives in the Android foreground service, not in the UI: the service is
 * the source of truth, and an Activity that is destroyed and recreated
 * re-reads state from here rather than reconnecting. That is what makes
 * backgrounding and returning feel seamless while history stays in memory only.
 *
 * One socket per channel — the server rejects a second `join` per socket.
 */
class SessionManager(
    /** Owns every session and the pending-echo timers; supplied by the service. */
    private val scope: CoroutineScope,
    initialUrl: String = Servers.DEFAULT_URL,
    private val transport: Transport,
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    /**
     * Where the server's MOTD is remembered between connections. Persistent on
     * Android: a restored session is never sent one, and the in-memory default
     * would be empty exactly when it is needed — on a fresh launch.
     */
    private val motdStore: MotdStore = InMemoryMotdStore(),
    private val bufferCapacity: Int = 500,
    /**
     * How long to wait for the server to echo our own message before marking it
     * unconfirmed. A normal echo returns in well under a second; this only has
     * to be longer than a bad connection's round trip.
     */
    private val echoTimeoutMillis: Long = 10_000,
    /**
     * How long a closed channel stays live and recoverable. Long enough to
     * notice the tab is gone and reach the undo, short enough that a channel
     * the user meant to leave is not still receiving on their behalf.
     */
    private val closeGraceMillis: Long = 8_000,
    /**
     * Whether someone arriving or leaving is worth a line in the transcript —
     * the site's "Join/left notify", on by default there and here.
     *
     * A supplier rather than a value: it is a user setting, changed from the
     * settings sheet while this manager is alive in the service, and each event
     * should honour what it says now. Read per event, as [chat.hc.core.session.Alert]
     * preferences are.
     *
     * Consulted *before* the buffer rather than at render time, which the site
     * has no need to do: the buffer is bounded and holds the only copy of the
     * conversation there is, so a busy channel's join spam would evict real
     * messages the server cannot re-serve. The roster is unaffected either way —
     * who is here is a different question from who just arrived.
     */
    private val showJoinLeave: () -> Boolean = { true },
    private val now: () -> Long,
    /** Random enough to correlate our echo; not security-sensitive. */
    private val customIdFactory: () -> String,
) {
    /**
     * Endpoint new sessions connect to. Changing it tears down every channel:
     * the channels, the nicks and the tokens all belong to the old server, and
     * carrying any of them across would be meaningless.
     */
    var serverUrl: String = initialUrl
        private set

    /** Shared deliberately: the server scores rate limit per address, not per socket. */
    private val governor = RateGovernor()

    private val sessions = LinkedHashMap<String, ChannelSession>()
    private val buffers = LinkedHashMap<String, ChannelBuffer>()
    private val jobs = LinkedHashMap<String, Job>()
    /** Pending [beginLeave] grace periods. Presence here means "closing". */
    private val closeTimers = LinkedHashMap<String, Job>()
    private val lock = Mutex()

    private val _channels = MutableStateFlow<Map<String, ChannelUi>>(emptyMap())
    val channels: StateFlow<Map<String, ChannelUi>> = _channels.asStateFlow()

    /**
     * Messages addressed to the user personally; see [Alert].
     *
     * Hot and lossy by design. Nothing here is load-bearing — the message is
     * already in the buffer either way — so a slow or absent collector must
     * never be able to stall the socket pipeline. With no subscriber (the UI is
     * bound but the service is not collecting, say) alerts are simply dropped,
     * which is the right answer: an alert is only meaningful when it arrives.
     */
    private val _alerts = MutableSharedFlow<Alert>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val alerts: SharedFlow<Alert> = _alerts.asSharedFlow()

    /** Channel the UI is currently showing; its messages never count as unread. */
    var activeChannel: String? = null
        set(value) {
            field = value
            value?.let { ch -> mutate(ch) { it.copy(unread = 0) } }
        }

    /**
     * Joins a channel, or changes who we are in one we are already in.
     *
     * The identity check is the point of the second case. hack.chat has no
     * rename, so becoming someone else is a leave and a fresh join — and the
     * alternative, treating any re-join as a no-op, is what made the join form
     * look like it ignored the nick you typed for a channel already on screen.
     * An identical identity stays a no-op: the server rejects a second `join`
     * on an established socket, and there would be nothing to change.
     */
    suspend fun join(channel: String, credentials: Credentials) {
        lock.withLock {
            // Joining a channel on its way out calls off the departure. Without
            // this, re-joining one you just closed with the same identity hits
            // the no-op below and leaves it hidden, waiting to disappear.
            closeTimers.remove(channel)?.let { timer ->
                timer.cancel()
                mutate(channel) { ui -> ui.copy(closing = false) }
            }

            val existing = sessions[channel]
            if (existing != null) {
                if (existing.credentials == credentials) return
                existing.stop()
                jobs.remove(channel)?.cancel()
                // The scrollback is kept: the server holds no history, so
                // dropping it to change nick would destroy the conversation.
                buffers[channel]?.add(
                    ChatMessage(
                        0,
                        MessageKind.Info,
                        text = "Rejoining as ${credentials.nick}.",
                        at = now(),
                    )
                )
                // The roster belongs to the session being retired, and its
                // `isme` entry is about to name the wrong person.
                mutate(channel) { it.copy(roster = emptyList(), state = SessionState.Connecting) }
            }

            val session = ChannelSession(
                channel = channel,
                credentials = credentials,
                url = serverUrl,
                transport = transport,
                governor = governor,
                tokenStore = tokenStore,
            )
            sessions[channel] = session
            buffers.getOrPut(channel) { ChannelBuffer(bufferCapacity) }
            _channels.update { it + (channel to (it[channel] ?: ChannelUi(channel))) }
            publish(channel)

            // Both collectors under one parent, so cancelling the entry stops
            // both. A state collector left running on a retired session would
            // keep writing its state over the replacement's.
            jobs[channel] = scope.launch {
                launch { session.events.collect { onEvent(session, it) } }
                launch { session.state.collect { st -> mutate(channel) { it.copy(state = st) } } }
            }
            session.start(scope)
        }
    }

    /**
     * Points the client at a different server, disconnecting everything first.
     *
     * Tokens are keyed by server, so the ones for the previous endpoint survive
     * untouched — switching to a local test server and back does not cost you a
     * silent resume on the server you normally use.
     */
    suspend fun setServer(url: String) {
        if (url == serverUrl) return
        stopAll()
        serverUrl = url
    }

    /**
     * Hides a channel and starts the clock on actually leaving it.
     *
     * Deferred rather than undone, because the two are not equivalent here: the
     * server keeps no history, so a leave-then-rejoin cannot restore the
     * transcript, and it costs the whole channel an `onlineRemove`/`onlineAdd`
     * pair — the same peer-visible noise `upstream-asks.md` exists to complain
     * about. Waiting instead means an undo emits nothing at all: as far as the
     * channel is concerned the user never left.
     *
     * Idempotent, so a second close of a channel already closing does not
     * restart its grace period.
     */
    suspend fun beginLeave(channel: String) {
        lock.withLock {
            if (channel !in sessions || channel in closeTimers) return
            mutate(channel) { it.copy(closing = true) }
            closeTimers[channel] = scope.launch {
                delay(closeGraceMillis)
                // Taken after the delay, not before, so the timer does not hold
                // the lock for the whole grace period. An undo that lands while
                // this is waiting cancels the job, and the guard covers the
                // sliver where it lands after the wait but before the lock.
                lock.withLock {
                    if (channel in closeTimers) commitLeave(channel)
                }
            }
        }
    }

    /** Takes back a [beginLeave] while its grace period is still running. */
    suspend fun undoLeave(channel: String) {
        lock.withLock {
            val timer = closeTimers.remove(channel) ?: return
            timer.cancel()
            mutate(channel) { it.copy(closing = false) }
        }
    }

    /** Leaves now, without waiting out the grace period. */
    suspend fun leave(channel: String) {
        lock.withLock {
            closeTimers.remove(channel)?.cancel()
            commitLeave(channel)
        }
    }

    /** Caller holds [lock]. */
    private suspend fun commitLeave(channel: String) {
        closeTimers.remove(channel)
        sessions.remove(channel)?.stop()
        jobs.remove(channel)?.cancel()
        buffers.remove(channel)
        _channels.update { it - channel }
    }

    suspend fun stopAll() {
        lock.withLock {
            closeTimers.values.forEach { it.cancel() }
            sessions.values.forEach { it.stop() }
            jobs.values.forEach { it.cancel() }
            sessions.clear(); jobs.clear(); buffers.clear(); closeTimers.clear()
            _channels.update { emptyMap() }
        }
    }

    /**
     * Sends optimistically: the message appears immediately as [Delivery.Sending]
     * and is reconciled when the server echoes it back with our customId.
     */
    suspend fun sendChat(channel: String, text: String) {
        val session = sessions[channel] ?: return
        val buffer = buffers[channel] ?: return

        // Slash commands are handled server-side and may produce an emote, an
        // info, a warn, or nothing at all — so there is no echo to reconcile
        // against and an optimistic bubble would hang at "sending…" forever.
        if (Composer.isSlashCommand(text)) {
            runCatching { session.send(Outbound.Chat(text)) }
            return
        }

        val customId = customIdFactory()
        buffer.addPending(text, customId, session.roster.firstOrNull { it.isme }?.nick ?: "", session.userid ?: 0L, now())
        publish(channel)
        runCatching { session.send(Outbound.Chat(text, customId)) }
            .onSuccess { scheduleEchoTimeout(channel, customId) }
            .onFailure {
                buffer.markFailed(customId)
                publish(channel)
            }
    }

    /**
     * A message can be written to the socket and still never come back: the
     * connection may drop, or the server may discard it without replying (an
     * oversized customId or a rate-limit penalty both do exactly that). Since
     * hack.chat keeps no history the echo can never arrive late, so a pending
     * message that ages out is downgraded rather than left spinning forever.
     */
    private fun scheduleEchoTimeout(channel: String, customId: String) {
        scope.launch {
            delay(echoTimeoutMillis)
            val buffer = buffers[channel] ?: return@launch
            if (buffer.markUnconfirmed(customId)) publish(channel)
        }
    }

    fun buffer(channel: String): ChannelBuffer? = buffers[channel]

    /** Our own roster entry, which carries the level the server assigned us. */
    fun me(channel: String): chat.hc.core.protocol.User? =
        sessions[channel]?.roster?.firstOrNull { it.isme }

    /**
     * Performs a moderation action, re-checking the level rather than trusting
     * the UI: a rejected command costs 10 rate-limit points of 25 and the
     * server replies nothing, so a stale button must not be able to spend them.
     */
    suspend fun moderate(channel: String, action: ModAction, target: chat.hc.core.protocol.User): Boolean {
        val session = sessions[channel] ?: return false
        val me = me(channel) ?: return false
        // The full check, not just the level gate: a target at or above our own
        // level is refused server-side too, and a stale button must not be able
        // to spend rate budget discovering that.
        if (action !in Moderation.available(me, target)) return false
        val frame = Moderation.frameFor(action, channel, target) ?: return false
        return runCatching { session.send(frame) }.isSuccess
    }

    /**
     * Invites [target] somewhere, and lets the server say where.
     *
     * `to` is deliberately omitted, so `invite.js#getChannel` invents a fresh
     * random channel instead of pointing at one of ours. That is what the site
     * does and what an invite usually means — somewhere new, for the two of
     * you. It also means the destination is never predicted here: the server
     * sends the same `invite` frame to both of us naming it, so where we are
     * going arrives through the ordinary receive path and is reported by
     * exactly one piece of code.
     *
     * A userid, never a nick. `invite.js` requires a numeric `userid` from a v2
     * socket and *silently drops* a nick-only payload — no reply at all, and
     * the rate-limit points spent anyway (see probe/FINDINGS.md §2), which is
     * the worst failure shape there is: indistinguishable from success.
     *
     * Re-checked rather than trusting the UI, as [moderate] is. Inviting
     * yourself is legal server-side and merely useless, so the guard is against
     * spending 2 rate-limit points of 25 on nothing rather than against a
     * rejection.
     */
    suspend fun invite(channel: String, target: chat.hc.core.protocol.User): Boolean {
        val session = sessions[channel] ?: return false
        val me = me(channel) ?: return false
        if (target.userid == me.userid) return false
        return runCatching {
            session.send(Outbound.Invite(channel = channel, userid = target.userid))
        }.isSuccess
    }

    private suspend fun onEvent(session: ChannelSession, event: SessionEvent) {
        val channel = event.channel
        val buffer = buffers[channel] ?: return

        when (event) {
            is SessionEvent.Message -> {
                val msg = buffer.applyChat(event.frame, session.userid)
                if (!msg.isMine) {
                    if (channel != activeChannel) {
                        mutate(channel) { it.copy(unread = it.unread + 1) }
                    }
                    alertIfMentioned(session, msg.nick, msg.text, event.frame.time ?: now())
                }
            }

            is SessionEvent.MessageUpdated -> buffer.applyUpdate(event.frame)

            // Emotes are alerted on too: `/me pokes @you` is someone talking to
            // you, and the third person is a grammatical choice rather than a
            // quieter one.
            is SessionEvent.Emote -> {
                buffer.add(
                    ChatMessage(0, MessageKind.Emote, event.frame.nick, event.frame.userid, text = event.frame.text, at = event.frame.time ?: now())
                )
                if (event.frame.userid != session.userid) {
                    alertIfMentioned(session, event.frame.nick, event.frame.text, event.frame.time ?: now())
                }
            }

            // The server sends the *same* frame to both parties, so direction
            // is only knowable by comparing `from` against our own userid.
            // Both ids are userids: resolve to a nick now, while the roster
            // still holds them — the sender may leave before we render again.
            is SessionEvent.Whisper -> {
                val who = WhisperResolver.resolve(
                    from = event.frame.from,
                    to = event.frame.to,
                    myUserid = session.userid,
                    lookupNick = { id -> session.roster.firstOrNull { it.userid == id }?.nick },
                )

                buffer.add(
                    ChatMessage(
                        localId = 0,
                        kind = if (who.outgoing) MessageKind.WhisperSent else MessageKind.Whisper,
                        nick = who.nick,
                        userid = who.otherId,
                        text = event.frame.text,
                        trip = event.frame.trip,
                        at = event.frame.time ?: now(),
                        isMine = who.outgoing,
                    )
                )
                // Our own outgoing whisper must not mark the channel unread.
                if (!who.outgoing) {
                    if (channel != activeChannel) {
                        mutate(channel) { ui -> ui.copy(unread = ui.unread + 1) }
                    }
                    // No mention test: a whisper was sent to one person and we
                    // are that person. Being addressed is what it is for.
                    _alerts.tryEmit(
                        Alert(
                            channel = channel,
                            kind = Alert.Kind.Whisper,
                            nick = who.nick,
                            text = event.frame.text,
                            at = event.frame.time ?: now(),
                        )
                    )
                }
            }

            is SessionEvent.Notice -> {
                // Kept because we will not be sent it again: the MOTD answers a
                // `join`, and every connection after the first restores a token
                // instead. See the Resumed branch, which replays it.
                if (event.frame.id == ErrorId.MOTD) motdStore.save(serverUrl, event.frame.text)
                buffer.add(
                    ChatMessage(0, MessageKind.Info, text = event.frame.text, at = event.frame.time ?: now())
                )
            }

            is SessionEvent.Warning -> buffer.add(
                ChatMessage(0, MessageKind.Warning, text = event.frame.text, at = event.frame.time ?: now())
            )

            is SessionEvent.UserJoined -> if (showJoinLeave()) buffer.add(
                ChatMessage(0, MessageKind.Join, event.user.nick, event.user.userid, at = now())
            )

            is SessionEvent.UserLeft -> if (showJoinLeave()) buffer.add(
                ChatMessage(0, MessageKind.Leave, event.nick, event.userid, at = now())
            )

            is SessionEvent.RosterChanged -> mutate(channel) { it.copy(roster = event.roster) }

            // A cold resume is peer-visible; a silent one is not. Worth noting in
            // the transcript so a reader can tell why join/leave noise appeared.
            is SessionEvent.Resumed -> {
                if (!event.silent && event.restored) {
                    buffer.add(ChatMessage(0, MessageKind.Info, text = "Reconnected.", at = now()))
                }
                // Mirrors client.js#onlineSet: the site answers "who is here"
                // in the transcript, not only in a sidebar. Emitted here rather
                // than on the onlineSet frame itself so it lands *below* the
                // reconnect notice — the roster is already current by now,
                // since the handshake has completed.
                //
                // Skipped on a silent handoff: nothing observable changed, and
                // repeating the roster would be pure noise.
                if (!event.silent) {
                    val nicks = session.roster.map { it.nick }
                    if (nicks.isNotEmpty()) {
                        buffer.add(
                            ChatMessage(
                                0,
                                MessageKind.Info,
                                text = "Users online: " + nicks.joinToString(", "),
                                at = now(),
                            )
                        )
                    }
                }

                // A restored socket is sent no MOTD, so the remembered one goes
                // here — below the roster line, which is where a cold join puts
                // it, since that arrives on `join` and this is emitted once the
                // handshake returns.
                if (!event.silent && event.restored) replayMotd(buffer)
            }

            // One notice per outage, not one per retry: a long outage produces a
            // Disconnected event on every backoff attempt, which otherwise fills
            // the transcript with identical lines.
            is SessionEvent.Disconnected -> {
                // Anything still waiting for an echo will never get one.
                buffer.markAllPendingUnconfirmed()
                val last = buffer.snapshot().lastOrNull()
                val alreadyReported = last?.kind == MessageKind.Info &&
                    last.text.startsWith("Disconnected")
                if (!alreadyReported) {
                    buffer.add(
                        ChatMessage(
                            0,
                            MessageKind.Info,
                            text = "Disconnected${event.cause?.let { ": $it" } ?: ""}",
                            at = now(),
                        )
                    )
                }
            }

            // Shaped like a whisper, and for the same reason: `invite.js`
            // sends the *identical* frame to the target and to the inviter, so
            // direction is only knowable from `from`, and both ends are userids
            // that have to be resolved against the roster while the sender is
            // still in it.
            is SessionEvent.Invited -> {
                val who = WhisperResolver.resolve(
                    from = event.frame.from,
                    to = event.frame.to,
                    myUserid = session.userid,
                    lookupNick = { id -> session.roster.firstOrNull { it.userid == id }?.nick },
                )
                val at = event.frame.time ?: now()

                // Info, which is the kind the site uses for this: a v1 socket
                // is sent the same event *as* an `info` frame, so rendering it
                // any other way would make the two clients disagree about
                // something they are both told.
                buffer.add(
                    ChatMessage(
                        localId = 0,
                        kind = MessageKind.Info,
                        text = InviteNotice.line(who.outgoing, who.nick, event.frame.inviteChannel),
                        at = at,
                    )
                )

                // Our own invite comes back to us too; it must not mark the
                // channel unread or buzz us about what we just did.
                if (!who.outgoing) {
                    if (channel != activeChannel) {
                        mutate(channel) { ui -> ui.copy(unread = ui.unread + 1) }
                    }
                    // No mention test, as with a whisper: an invite is sent to
                    // one person and we are that person. The site notifies on
                    // one as well (`client.js` tests `type === 'invite'`), and
                    // it is more time-sensitive than most things said to you —
                    // it points at where the conversation is moving.
                    _alerts.tryEmit(
                        Alert(
                            channel = channel,
                            kind = Alert.Kind.Invite,
                            nick = who.nick,
                            text = InviteNotice.alert(event.frame.inviteChannel),
                            at = at,
                        )
                    )
                }
            }

            is SessionEvent.UnknownFrame -> Unit
        }
        publish(channel)
    }

    /**
     * Raises a [Alert.Kind.Mention] if [text] says `@us`.
     *
     * The roster is asked first and the credentials are only a fallback: the
     * server has the last word on what we are called — a nick collision or a
     * token restore can both leave us answering to something other than what we
     * asked for — but the roster is empty until the handshake completes, and a
     * mention arriving in that window should still land.
     */
    private fun alertIfMentioned(session: ChannelSession, from: String, text: String, at: Long) {
        val me = session.roster.firstOrNull { it.isme }?.nick ?: session.credentials.nick
        if (!Mentions.mentions(text, me)) return
        _alerts.tryEmit(
            Alert(session.channel, Alert.Kind.Mention, nick = from, text = text, at = at)
        )
    }

    /**
     * Shows the remembered MOTD on a connection that was not sent one.
     *
     * Skipped when the transcript already shows it, which is what keeps a
     * reconnect from repeating it: an outage leaves the buffer intact, so only
     * a genuinely empty one — a fresh launch — sees the line again. In a
     * channel busy enough to evict it from a bounded buffer it can reappear,
     * which is the same thing that would happen to any other old message and
     * the honest answer to "is it still there".
     */
    private suspend fun replayMotd(buffer: ChannelBuffer) {
        val motd = motdStore.load(serverUrl)?.takeIf { it.isNotBlank() } ?: return
        if (buffer.snapshot().any { it.kind == MessageKind.Info && it.text == motd }) return
        buffer.add(ChatMessage(0, MessageKind.Info, text = motd, at = now()))
    }

    private fun publish(channel: String) {
        val buffer = buffers[channel] ?: return
        mutate(channel) { it.copy(messages = buffer.snapshot()) }
    }

    /**
     * Must be atomic: connection state and message arrival are collected by
     * separate coroutines on a multi-threaded dispatcher. A plain
     * read-modify-write loses updates — in practice the `Live` transition was
     * being clobbered by a concurrent message publish holding a stale map, so
     * the UI sat on "connecting…" while messages streamed in.
     */
    private fun mutate(channel: String, block: (ChannelUi) -> ChannelUi) {
        _channels.update { current ->
            val existing = current[channel] ?: return@update current
            current + (channel to block(existing))
        }
    }

    val totalUnread: Int get() = _channels.value.values.sumOf { it.unread }
}
