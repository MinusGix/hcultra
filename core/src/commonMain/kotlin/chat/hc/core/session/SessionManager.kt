package chat.hc.core.session

import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import chat.hc.core.protocol.ErrorId
import chat.hc.core.protocol.Outbound
import chat.hc.core.store.ChannelBuffer
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.HistoryLimit
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
    val key: ChannelKey,
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
) {
    val channel: String get() = key.channel
    val server: String get() = key.server
}

/**
 * Owns every channel session, their buffers, and the single shared rate budget.
 *
 * This lives in the Android foreground service, not in the UI: the service is
 * the source of truth, and an Activity that is destroyed and recreated
 * re-reads state from here rather than reconnecting. That is what makes
 * backgrounding and returning feel seamless while history stays in memory only.
 *
 * One socket per channel, by choice since the 2026-10 server update (a v2
 * socket may now join several); see [ChannelSession].
 *
 * Channels on several servers can be open at once, each keyed by
 * [ChannelKey]. What belongs to a server rather than a channel — the rate
 * budget, the MOTD, which [Dialect] it speaks — is held per server.
 */
class SessionManager(
    /** Owns every session and the pending-echo timers; supplied by the service. */
    private val scope: CoroutineScope,
    private val transport: Transport,
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    /**
     * Where the server's MOTD is remembered between connections. Persistent on
     * Android: a restored session is never sent one, and the in-memory default
     * would be empty exactly when it is needed — on a fresh launch.
     */
    private val motdStore: MotdStore = InMemoryMotdStore(),
    /**
     * How much of each channel to keep. A supplier, like [showJoinLeave], since
     * it is a user setting that changes while this manager lives in the
     * service; it is read on every publish, so a lowered limit takes effect
     * with the next message and [enforceHistory] covers channels gone quiet.
     */
    private val historyLimit: () -> HistoryLimit = { HistoryLimit() },
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
    /**
     * What the user has said each server speaks. A supplier, read at every
     * connection, since it is a setting that changes while this lives on in
     * the service; see [forgetServer] for what a change costs.
     */
    private val dialectFor: (server: String) -> Dialect = { Dialect.Auto },
    /**
     * Whether a server's slash commands are ours to run; see [ClientCommands].
     * Known per instance rather than set by the user: it is a fact about which
     * server is running there.
     */
    private val clientCommands: (server: String) -> Boolean = { Servers.known(it)?.clientCommands == true },
) {
    /**
     * One per server, shared by every socket to it: the server scores rate
     * limit per address, not per socket. Two servers are two budgets.
     */
    private val governors = HashMap<String, RateGovernor>()

    /**
     * What [Dialect.Auto] found each server to speak, so that only the first
     * connection to one pays for finding out. Memory only: a server can be
     * upgraded, and a fresh start is a cheap way to notice. A flow only for
     * its atomic update: sessions report from their own threads.
     */
    private val detected = MutableStateFlow<Map<String, Dialect>>(emptyMap())

    /** Expression ignores, per server, for those with [clientCommands]. */
    private val filters = HashMap<String, ClientCommands.Filters>()

    private val sessions = LinkedHashMap<ChannelKey, ChannelSession>()
    private val buffers = LinkedHashMap<ChannelKey, ChannelBuffer>()
    private val jobs = LinkedHashMap<ChannelKey, Job>()
    /** Pending [beginLeave] grace periods. Presence here means "closing". */
    private val closeTimers = LinkedHashMap<ChannelKey, Job>()
    private val lock = Mutex()

    private val _channels = MutableStateFlow<Map<ChannelKey, ChannelUi>>(emptyMap())
    val channels: StateFlow<Map<ChannelKey, ChannelUi>> = _channels.asStateFlow()

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

    private val _ignored = MutableStateFlow(IgnoreList())

    /**
     * Nicks and trips whose messages the user has asked not to hear about.
     *
     * Across every server, since the same people are on more than one and
     * the list is something the user decided about them, not about a room.
     * By nick, as the site's own ignore is: a userid is per connection, so
     * keying on one would let anyone back in by reconnecting. By trip as well,
     * since a nick can be changed and a trip cannot without the password.
     * Held here rather than in the UI because this is what decides unread
     * counts and [alerts], and the service outlives every Activity; it lasts
     * as long as the manager, which is roughly as long as the site's lasts in
     * a tab.
     *
     * Their messages are still buffered. Hiding them is the renderer's job, so
     * that stopping ignoring someone shows what they said in the meantime; what
     * is decided here is only that none of it marks a tab unread or alerts.
     */
    val ignored: StateFlow<IgnoreList> = _ignored.asStateFlow()

    fun setIgnored(nick: String, ignore: Boolean) {
        _ignored.update { it.copy(nicks = if (ignore) it.nicks + nick else it.nicks - nick) }
    }

    fun setTripIgnored(trip: String, ignore: Boolean) {
        if (trip.isBlank()) return
        _ignored.update { it.copy(trips = if (ignore) it.trips + trip else it.trips - trip) }
    }

    private fun isIgnored(nick: String, trip: String?) = _ignored.value.matches(nick, trip)

    /** Channel the UI is currently showing; its messages never count as unread. */
    var activeChannel: ChannelKey? = null
        set(value) {
            field = value
            value?.let { key -> mutate(key) { it.copy(unread = 0) } }
        }

    /** What a server speaks: the user's setting, or what Auto found out. */
    private fun dialectOf(server: String): Dialect {
        val chosen = dialectFor(server)
        if (chosen != Dialect.Auto) return chosen
        return detected.value[server] ?: Dialect.Auto
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
    suspend fun join(key: ChannelKey, credentials: Credentials) {
        lock.withLock {
            // Joining a channel on its way out calls off the departure. Without
            // this, re-joining one you just closed with the same identity hits
            // the no-op below and leaves it hidden, waiting to disappear.
            closeTimers.remove(key)?.let { timer ->
                timer.cancel()
                mutate(key) { ui -> ui.copy(closing = false) }
            }

            val existing = sessions[key]
            if (existing != null) {
                if (existing.credentials == credentials) return
                existing.stop()
                jobs.remove(key)?.cancel()
                // The scrollback is kept: the server holds no history, so
                // dropping it to change nick would destroy the conversation.
                buffers[key]?.add(
                    ChatMessage(
                        0,
                        MessageKind.Info,
                        text = "Rejoining as ${credentials.nick}.",
                        at = now(),
                    )
                )
                // The roster belongs to the session being retired, and its
                // `isme` entry is about to name the wrong person.
                mutate(key) { it.copy(roster = emptyList(), state = SessionState.Connecting) }
            }

            val server = key.server
            val session = ChannelSession(
                channel = key.channel,
                credentials = credentials,
                url = server,
                transport = transport,
                governor = governors.getOrPut(server) { RateGovernor() },
                tokenStore = tokenStore,
                dialect = { dialectOf(server) },
                onDialect = { found -> detected.update { it + (server to found) } },
            )
            sessions[key] = session
            buffers.getOrPut(key) { ChannelBuffer(historyLimit().maxLines) }
            _channels.update { it + (key to (it[key] ?: ChannelUi(key))) }
            publish(key)

            // Both collectors under one parent, so cancelling the entry stops
            // both. A state collector left running on a retired session would
            // keep writing its state over the replacement's.
            jobs[key] = scope.launch {
                launch { session.events.collect { onEvent(key, session, it) } }
                launch { session.state.collect { st -> mutate(key) { it.copy(state = st) } } }
            }
            session.start(scope)
        }
    }

    /**
     * Leaves every channel on [server] at once, and forgets what Auto learned
     * about it — for a server whose address or dialect has just been changed,
     * or that has been removed. The channels belong to the old settings; the
     * next join connects under the new ones.
     *
     * Tokens are keyed by server and survive untouched, so pointing a server
     * entry somewhere else and back does not cost a silent resume.
     */
    suspend fun forgetServer(server: String) {
        lock.withLock {
            detected.update { it - server }
            sessions.keys.filter { it.server == server }.forEach { key ->
                closeTimers.remove(key)?.cancel()
                commitLeave(key)
            }
        }
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
    suspend fun beginLeave(key: ChannelKey) {
        lock.withLock {
            if (key !in sessions || key in closeTimers) return
            mutate(key) { it.copy(closing = true) }
            closeTimers[key] = scope.launch {
                delay(closeGraceMillis)
                // Taken after the delay, not before, so the timer does not hold
                // the lock for the whole grace period. An undo that lands while
                // this is waiting cancels the job, and the guard covers the
                // sliver where it lands after the wait but before the lock.
                lock.withLock {
                    if (key in closeTimers) commitLeave(key)
                }
            }
        }
    }

    /** Takes back a [beginLeave] while its grace period is still running. */
    suspend fun undoLeave(key: ChannelKey) {
        lock.withLock {
            val timer = closeTimers.remove(key) ?: return
            timer.cancel()
            mutate(key) { it.copy(closing = false) }
        }
    }

    /** Leaves now, without waiting out the grace period. */
    suspend fun leave(key: ChannelKey) {
        lock.withLock {
            closeTimers.remove(key)?.cancel()
            commitLeave(key)
        }
    }

    /** Caller holds [lock]. */
    private suspend fun commitLeave(key: ChannelKey) {
        closeTimers.remove(key)
        sessions.remove(key)?.stop()
        jobs.remove(key)?.cancel()
        buffers.remove(key)
        _channels.update { it - key }
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
    suspend fun sendChat(key: ChannelKey, text: String) {
        val session = sessions[key] ?: return
        val buffer = buffers[key] ?: return

        if (clientCommands(key.server)) {
            ClientCommands.parse(text, key.channel)?.let { action ->
                runClientCommand(key, session, buffer, action)
                return
            }
        }

        // Slash commands are handled server-side and may produce an emote, an
        // info, a warn, or nothing at all — so there is no echo to reconcile
        // against and an optimistic bubble would hang at "sending…" forever.
        if (Composer.isSlashCommand(text)) {
            runCatching { session.send(Outbound.Chat(key.channel, text)) }
            return
        }

        val customId = customIdFactory()
        buffer.addPending(text, customId, session.roster.firstOrNull { it.isme }?.nick ?: "", session.userid ?: 0L, now())
        publish(key)
        runCatching { session.send(Outbound.Chat(key.channel, text, customId)) }
            .onSuccess { scheduleEchoTimeout(key, customId) }
            .onFailure {
                buffer.markFailed(customId)
                publish(key)
            }
    }

    /** What the site's own client would have done with a command; see [ClientCommands]. */
    private suspend fun runClientCommand(
        key: ChannelKey,
        session: ChannelSession,
        buffer: ChannelBuffer,
        action: ClientCommands.Action,
    ) {
        val showIgnored = {
            val f = filters.getOrPut(key.server) { ClientCommands.Filters() }
            ClientCommands.ignoreListText(_ignored.value.nicks, f.always, f.first)
        }
        val note = when (action) {
            is ClientCommands.Action.Send -> {
                runCatching { session.send(action.frame) }
                null
            }
            is ClientCommands.Action.Ignore -> { setIgnored(action.nick, true); showIgnored() }
            is ClientCommands.Action.Unignore -> { setIgnored(action.nick, false); showIgnored() }
            ClientCommands.Action.ShowIgnored -> showIgnored()
            ClientCommands.Action.ClearIgnored -> {
                _ignored.update { it.copy(nicks = emptySet()) }
                "Ignore list cleared"
            }
            is ClientCommands.Action.IgnoreExpression -> {
                filters.getOrPut(key.server) { ClientCommands.Filters() }.add(action.pattern, action.firstOnly)
                showIgnored()
            }
            is ClientCommands.Action.Notice -> action.text
        } ?: return
        buffer.add(ChatMessage(0, MessageKind.Info, text = note, at = now()))
        publish(key)
    }

    /**
     * Answers the captcha or password a channel is holding its join for — see
     * [SessionState.Challenged]. Not a chat: nothing is buffered or echoed, and
     * the server never broadcasts it.
     */
    fun answerChallenge(key: ChannelKey, text: String) {
        sessions[key]?.answer(text)
    }

    /**
     * A message can be written to the socket and still never come back: the
     * connection may drop, or the server may discard it without replying (an
     * oversized customId or a rate-limit penalty both do exactly that). Since
     * hack.chat keeps no history the echo can never arrive late, so a pending
     * message that ages out is downgraded rather than left spinning forever.
     */
    private fun scheduleEchoTimeout(key: ChannelKey, customId: String) {
        scope.launch {
            delay(echoTimeoutMillis)
            val buffer = buffers[key] ?: return@launch
            if (buffer.markUnconfirmed(customId)) publish(key)
        }
    }

    fun buffer(key: ChannelKey): ChannelBuffer? = buffers[key]

    /** Our own roster entry, which carries the level the server assigned us. */
    fun me(key: ChannelKey): chat.hc.core.protocol.User? =
        sessions[key]?.roster?.firstOrNull { it.isme }

    /**
     * Performs a moderation action, re-checking the level rather than trusting
     * the UI: a rejected command costs 10 rate-limit points of 25 and the
     * server replies nothing, so a stale button must not be able to spend them.
     */
    suspend fun moderate(key: ChannelKey, action: ModAction, target: chat.hc.core.protocol.User): Boolean {
        val session = sessions[key] ?: return false
        val me = me(key) ?: return false
        // The full check, not just the level gate: a target at or above our own
        // level is refused server-side too, and a stale button must not be able
        // to spend rate budget discovering that.
        if (action !in Moderation.available(me, target)) return false
        val frame = Moderation.frameFor(action, key.channel, target, session.legacy) ?: return false
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
     * A userid, never a nick. `invite.js` used to require a numeric `userid`
     * from a v2 socket and *silently drop* a nick-only payload; since the
     * 2026-10 update it accepts either, but a userid cannot be ambiguous, and a
     * payload naming no one is still dropped with no reply and the rate-limit
     * points spent (see probe/FINDINGS.md §2) — the worst failure shape there
     * is: indistinguishable from success.
     *
     * Re-checked rather than trusting the UI, as [moderate] is. Inviting
     * yourself is legal server-side and merely useless, so the guard is against
     * spending 2 rate-limit points of 25 on nothing rather than against a
     * rejection.
     *
     * By nick on a v1 connection, the only way a v1 `invite` can be addressed.
     */
    suspend fun invite(key: ChannelKey, target: chat.hc.core.protocol.User): Boolean {
        val session = sessions[key] ?: return false
        val me = me(key) ?: return false
        if (target.userid == me.userid) return false
        val frame = if (session.legacy) Outbound.Invite(channel = key.channel, nick = target.nick)
        else Outbound.Invite(channel = key.channel, userid = target.userid)
        return runCatching { session.send(frame) }.isSuccess
    }

    /**
     * Events say which channel they are about by name, which is ambiguous
     * across servers, so the key comes from the session they were collected
     * from instead.
     */
    private suspend fun onEvent(channel: ChannelKey, session: ChannelSession, event: SessionEvent) {
        val buffer = buffers[channel] ?: return

        when (event) {
            is SessionEvent.Message -> {
                // Not buffered at all, as the site never shows them: these are
                // walls of spam, and the buffer is all the history there is.
                if (clientCommands(channel.server) && event.frame.userid != session.userid &&
                    filters.getOrPut(channel.server) { ClientCommands.Filters() }
                        .hides(channel.channel, event.frame.nick, event.frame.text)
                ) return
                val msg = buffer.applyChat(event.frame, session.userid, receivedAt = now())
                if (!msg.isMine && !isIgnored(msg.nick, msg.trip)) {
                    if (channel != activeChannel) {
                        mutate(channel) { it.copy(unread = it.unread + 1) }
                    }
                    alertIfMentioned(channel, session, msg.nick, msg.text, event.frame.time ?: now())
                }
            }

            is SessionEvent.MessageUpdated -> buffer.applyUpdate(event.frame)

            // Emotes are chat in the third person, and are treated as chat:
            // `/me pokes @you` is someone talking to you, so it alerts, and
            // `/me` is saying something to the room, so it counts as unread.
            // The grammar is a choice of voice, not a quieter kind of message.
            is SessionEvent.Emote -> {
                val mine = event.frame.userid == session.userid
                buffer.add(
                    ChatMessage(
                        0, MessageKind.Emote, event.frame.nick, event.frame.userid,
                        trip = event.frame.trip,
                        text = event.frame.text, at = event.frame.time ?: now(), isMine = mine,
                    )
                )
                if (!mine && !isIgnored(event.frame.nick, event.frame.trip)) {
                    if (channel != activeChannel) {
                        mutate(channel) { it.copy(unread = it.unread + 1) }
                    }
                    alertIfMentioned(channel, session, event.frame.nick, event.frame.text, event.frame.time ?: now())
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
                if (!who.outgoing && !isIgnored(who.nick, event.frame.trip)) {
                    if (channel != activeChannel) {
                        mutate(channel) { ui -> ui.copy(unread = ui.unread + 1) }
                    }
                    // No mention test: a whisper was sent to one person and we
                    // are that person. Being addressed is what it is for.
                    _alerts.tryEmit(
                        Alert(
                            key = channel,
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
                if (event.frame.id == ErrorId.MOTD) motdStore.save(channel.server, event.frame.text)
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
                if (!event.silent && event.restored) replayMotd(channel.server, buffer)
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
                // The frame carries no trip, so it is read off the roster the
                // same way the nick is, for an ignore that went by trip.
                val inviterTrip = session.roster.firstOrNull { it.userid == event.frame.from }?.trip

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
                // channel unread or buzz us about what we just did. An ignored
                // inviter's line is still kept: it is an info line with no
                // nick of its own, so nothing could hide it and then show it
                // again, and dropping it outright would be unrecoverable.
                if (!who.outgoing && !isIgnored(who.nick, inviterTrip)) {
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
                            key = channel,
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
    private fun alertIfMentioned(key: ChannelKey, session: ChannelSession, from: String, text: String, at: Long) {
        val me = session.roster.firstOrNull { it.isme }?.nick ?: session.credentials.nick
        if (!Mentions.mentions(text, me)) return
        _alerts.tryEmit(
            Alert(key, Alert.Kind.Mention, nick = from, text = text, at = at)
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
    private suspend fun replayMotd(server: String, buffer: ChannelBuffer) {
        val motd = motdStore.load(server)?.takeIf { it.isNotBlank() } ?: return
        if (buffer.snapshot().any { it.kind == MessageKind.Info && it.text == motd }) return
        buffer.add(ChatMessage(0, MessageKind.Info, text = motd, at = now()))
    }

    private fun publish(channel: ChannelKey) {
        val buffer = buffers[channel] ?: return
        buffer.enforce(historyLimit(), now())
        mutate(channel) { it.copy(messages = buffer.snapshot()) }
    }

    /**
     * Puts [messages] into a joined channel's history as if they had arrived,
     * without the server. For exercising long histories on a device — nothing
     * outside a debug build calls it. Ids are assigned here; the ones passed
     * in are ignored. Returns false if the channel is not open.
     */
    fun injectForTesting(channel: ChannelKey, messages: List<ChatMessage>): Boolean {
        val buffer = buffers[channel] ?: return false
        messages.forEach { buffer.add(it) }
        if (channel != activeChannel) mutate(channel) { it.copy(unread = it.unread + messages.size) }
        publish(channel)
        return true
    }

    /**
     * Applies the history limit to every channel now, rather than at its next
     * message. For a changed setting, and for the age limit, which a quiet
     * channel would otherwise never reach: nothing arrives to trigger a publish.
     */
    suspend fun enforceHistory() {
        // Under the lock because it walks the channel map, which join and leave
        // change; the buffers guard themselves.
        lock.withLock {
            val limit = historyLimit()
            val at = now()
            buffers.keys.forEach { channel ->
                if (buffers[channel]?.enforce(limit, at) == true) publish(channel)
            }
        }
    }

    /**
     * Must be atomic: connection state and message arrival are collected by
     * separate coroutines on a multi-threaded dispatcher. A plain
     * read-modify-write loses updates — in practice the `Live` transition was
     * being clobbered by a concurrent message publish holding a stale map, so
     * the UI sat on "connecting…" while messages streamed in.
     */
    private fun mutate(channel: ChannelKey, block: (ChannelUi) -> ChannelUi) {
        _channels.update { current ->
            val existing = current[channel] ?: return@update current
            current + (channel to block(existing))
        }
    }

    val totalUnread: Int get() = _channels.value.values.sumOf { it.unread }
}
