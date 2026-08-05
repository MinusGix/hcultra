package chat.hc.core.session

import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import chat.hc.core.protocol.Outbound
import chat.hc.core.store.ChannelBuffer
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val bufferCapacity: Int = 500,
    /**
     * How long to wait for the server to echo our own message before marking it
     * unconfirmed. A normal echo returns in well under a second; this only has
     * to be longer than a bad connection's round trip.
     */
    private val echoTimeoutMillis: Long = 10_000,
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
    private val lock = Mutex()

    private val _channels = MutableStateFlow<Map<String, ChannelUi>>(emptyMap())
    val channels: StateFlow<Map<String, ChannelUi>> = _channels.asStateFlow()

    /** Channel the UI is currently showing; its messages never count as unread. */
    var activeChannel: String? = null
        set(value) {
            field = value
            value?.let { ch -> mutate(ch) { it.copy(unread = 0) } }
        }

    suspend fun join(channel: String, credentials: Credentials) {
        lock.withLock {
            if (sessions.containsKey(channel)) return
            val session = ChannelSession(
                channel = channel,
                credentials = credentials,
                url = serverUrl,
                transport = transport,
                governor = governor,
                tokenStore = tokenStore,
            )
            sessions[channel] = session
            buffers[channel] = ChannelBuffer(bufferCapacity)
            _channels.update { it + (channel to ChannelUi(channel)) }

            jobs[channel] = scope.launch {
                session.events.collect { onEvent(session, it) }
            }
            scope.launch {
                session.state.collect { st -> mutate(channel) { it.copy(state = st) } }
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

    suspend fun leave(channel: String) {
        lock.withLock {
            sessions.remove(channel)?.stop()
            jobs.remove(channel)?.cancel()
            buffers.remove(channel)
            _channels.update { it - channel }
        }
    }

    suspend fun stopAll() {
        lock.withLock {
            sessions.values.forEach { it.stop() }
            jobs.values.forEach { it.cancel() }
            sessions.clear(); jobs.clear(); buffers.clear()
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

    private suspend fun onEvent(session: ChannelSession, event: SessionEvent) {
        val channel = event.channel
        val buffer = buffers[channel] ?: return

        when (event) {
            is SessionEvent.Message -> {
                val msg = buffer.applyChat(event.frame, session.userid)
                if (!msg.isMine && channel != activeChannel) {
                    mutate(channel) { it.copy(unread = it.unread + 1) }
                }
            }

            is SessionEvent.MessageUpdated -> buffer.applyUpdate(event.frame)

            is SessionEvent.Emote -> buffer.add(
                ChatMessage(0, MessageKind.Emote, event.frame.nick, event.frame.userid, text = event.frame.text, at = event.frame.time ?: now())
            )

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
                if (!who.outgoing && channel != activeChannel) {
                    mutate(channel) { ui -> ui.copy(unread = ui.unread + 1) }
                }
            }

            is SessionEvent.Notice -> buffer.add(
                ChatMessage(0, MessageKind.Info, text = event.frame.text, at = event.frame.time ?: now())
            )

            is SessionEvent.Warning -> buffer.add(
                ChatMessage(0, MessageKind.Warning, text = event.frame.text, at = event.frame.time ?: now())
            )

            is SessionEvent.UserJoined -> buffer.add(
                ChatMessage(0, MessageKind.Join, event.user.nick, event.user.userid, at = now())
            )

            is SessionEvent.UserLeft -> buffer.add(
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

            is SessionEvent.Invited, is SessionEvent.UnknownFrame -> Unit
        }
        publish(channel)
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
