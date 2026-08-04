package chat.hc.core.session

import chat.hc.core.net.Connection
import chat.hc.core.net.ConnectionClosedException
import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import chat.hc.core.protocol.ErrorId
import chat.hc.core.protocol.FrameCodec
import chat.hc.core.protocol.Inbound
import chat.hc.core.protocol.Limits
import chat.hc.core.protocol.Outbound
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * One channel, one socket.
 *
 * The server rejects a second `join` on an established socket
 * (`warn id 33`, verified on live), so multi-channel means multiple sessions.
 * The session token already carries a `channels` array and `session.js`
 * restores every entry, so if upstream finishes multichannel this collapses to
 * one socket without the layers above noticing.
 *
 * Lifecycle rules this class exists to enforce, all verified in probe/FINDINGS.md:
 *  1. The **first frame on every socket must be `session`** — that is what
 *     selects protocol v2. Sending `join` first permanently downgrades the
 *     socket to the legacy dialect, where whispers and invites arrive as prose
 *     `info` frames.
 *  2. The post-join token arrives **after** `onlineSet` and after the MOTD, so
 *     the handshake is not complete at `onlineSet`.
 *  3. Reconnect is only silent if the old socket is still open when the new one
 *     restores (make-before-break). A dropped socket cannot be silent.
 */
class ChannelSession(
    val channel: String,
    private val credentials: Credentials,
    private val url: String,
    private val transport: Transport,
    private val governor: RateGovernor,
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    private val backoff: Backoff = Backoff(),
    private val handshakeTimeoutMillis: Long = 10_000,
    private val tokenGraceMillis: Long = 5_000,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val presence = PresenceTracker()
    val roster get() = presence.roster

    /** Our own userid, learned at join and stable across restores. */
    var userid: Long? = null
        private set

    private var current: Connection? = null
    private var supervisor: Job? = null
    private var stopped = false
    /** Set when the next resume is expected to be invisible to peers. */
    private var expectSilentResume = false

    fun start(scope: CoroutineScope) {
        if (supervisor != null) return
        stopped = false
        supervisor = scope.launch { supervise(this) }
    }

    suspend fun stop() {
        stopped = true
        supervisor?.cancel()
        supervisor = null
        current?.close()
        current = null
        _state.value = SessionState.Idle
    }

    suspend fun send(frame: Outbound) {
        validate(frame)
        val conn = current ?: throw ConnectionClosedException("$channel: not connected")
        governor.spend(costOf(frame))
        conn.send(FrameCodec.encode(frame))
    }

    /**
     * Refuse frames the server would silently discard.
     *
     * An oversized customId is dropped by chat.js with no reply *and* charges 13
     * rate-limit points of 25. Failing loudly here turns an invisible
     * message-loss-plus-throttle into an ordinary error the UI can show.
     */
    private fun validate(frame: Outbound) {
        val customId = when (frame) {
            is Outbound.Chat -> frame.customId
            is Outbound.UpdateMessage -> frame.customId
            else -> null
        } ?: return
        if (customId.length > Limits.MAX_CUSTOM_ID_LENGTH) {
            throw InvalidFrameException(
                "customId '$customId' is ${customId.length} chars; " +
                    "server drops anything over ${Limits.MAX_CUSTOM_ID_LENGTH} and charges 13 rate points"
            )
        }
    }

    suspend fun sendChat(text: String, customId: String? = null) =
        send(Outbound.Chat(text, customId))

    /**
     * Proactively replace the connection while the old one still works, so
     * peers see only `updateUser {online:true}` instead of a leave/join pair.
     *
     * Use on network change (wifi↔cellular) and token refresh. It cannot help
     * after a drop — a socket the OS killed leaves nothing to overlap with,
     * which is exactly why the grace period is the upstream ask.
     */
    suspend fun handoff(scope: CoroutineScope, reason: HandoffReason) {
        if (reason == HandoffReason.Dropped) return
        val old = current ?: return
        if (!old.isOpen) return
        expectSilentResume = true
        // Bring the replacement fully up *before* retiring the old socket:
        // disconnect.js suppresses onlineRemove only while a duplicate userid
        // is still present in the channel.
        val fresh = connectAndHandshake(scope, tokenStore.load(url, channel))
        current = fresh.connection
        old.close()
        emit(SessionEvent.Resumed(channel, fresh.restored, silent = true))
        pump(fresh)
    }

    private suspend fun supervise(scope: CoroutineScope) {
        var attempt = 0
        while (scope.isActive && !stopped) {
            try {
                _state.value = SessionState.Connecting
                val live = connectAndHandshake(scope, tokenStore.load(url, channel))
                current = live.connection
                attempt = 0
                _state.value = SessionState.Live(live.restored)
                emit(SessionEvent.Resumed(channel, live.restored, silent = expectSilentResume))
                expectSilentResume = false
                pump(live)
                emit(SessionEvent.Disconnected(channel, null))
            } catch (c: CancellationException) {
                throw c
            } catch (e: FatalSessionException) {
                _state.value = SessionState.Failed(e.message ?: "fatal")
                emit(SessionEvent.Disconnected(channel, e.message))
                return
            } catch (e: Exception) {
                emit(SessionEvent.Disconnected(channel, e.message))
            }

            current = null
            if (stopped) break

            attempt += 1
            val wait = backoff.delayFor(attempt)
            _state.value = SessionState.Reconnecting(attempt, wait)
            delay(wait)
        }
    }

    private class LiveConnection(
        val connection: Connection,
        val frames: ReceiveChannel<Inbound>,
        val restored: Boolean,
    )

    private suspend fun connectAndHandshake(scope: CoroutineScope, token: String?): LiveConnection {
        _state.value = SessionState.Handshaking
        val conn = transport.open(url)
        val frames = conn.incoming.map(FrameCodec::decode).produceIn(scope)

        // Frame 1, always: declares protocol v2 whether or not we hold a token.
        governor.spend(RateGovernor.Cost.SESSION)
        conn.send(FrameCodec.encode(Outbound.Session(token)))

        val session = awaitFrame(frames, "session reply") { it is Inbound.Session } as Inbound.Session
        if (session.token.isNotEmpty()) tokenStore.save(url, channel, session.token)

        if (session.restored) {
            // restoreJoin replies with a fresh onlineSet for the restored channel.
            runCatching {
                awaitFrame(frames, "restored onlineSet") { it is Inbound.OnlineSet }
            }.onSuccess { dispatch(it) }
            return LiveConnection(conn, frames, restored = true)
        }

        governor.spend(RateGovernor.Cost.JOIN)
        conn.send(FrameCodec.encode(Outbound.Join(channel, credentials.nick, credentials.pass)))

        val joined = awaitFrame(frames, "onlineSet") {
            it is Inbound.OnlineSet || (it is Inbound.Warn && it.isJoinFailure())
        }
        if (joined is Inbound.Warn) {
            throw FatalSessionException("join refused (id ${joined.id}): ${joined.text}")
        }
        dispatch(joined)

        // The token trails onlineSet and the MOTD. Missing it is not fatal —
        // we simply lose silent-resume until the next token arrives.
        try {
            withTimeout(tokenGraceMillis) {
                val tok = awaitFrame(frames, "post-join token") {
                    it is Inbound.Session && it.token.isNotEmpty()
                } as Inbound.Session
                tokenStore.save(url, channel, tok.token)
            }
        } catch (_: TimeoutCancellationException) {
            // Leave the old token in place; a later frame may still carry one.
        }

        return LiveConnection(conn, frames, restored = false)
    }

    /** Reads until the connection closes. */
    private suspend fun pump(live: LiveConnection) {
        for (frame in live.frames) dispatch(frame)
    }

    /**
     * Waits for a matching frame, dispatching everything else on the way — the
     * MOTD, peer joins and even chat can arrive mid-handshake and must not be
     * dropped on the floor.
     */
    private suspend fun awaitFrame(
        frames: ReceiveChannel<Inbound>,
        label: String,
        predicate: (Inbound) -> Boolean,
    ): Inbound = withTimeout(handshakeTimeoutMillis) {
        for (frame in frames) {
            if (predicate(frame)) return@withTimeout frame
            dispatch(frame)
        }
        throw ConnectionClosedException("$channel: closed while awaiting $label")
    }

    private suspend fun dispatch(frame: Inbound) {
        when (frame) {
            is Inbound.OnlineSet -> {
                presence.reset(frame.users)
                userid = frame.users.firstOrNull { it.isme }?.userid ?: userid
                emit(SessionEvent.RosterChanged(channel, presence.roster))
            }

            is Inbound.OnlineAdd -> {
                val user = frame.toUser()
                presence.add(user)
                emit(SessionEvent.UserJoined(channel, user))
                emit(SessionEvent.RosterChanged(channel, presence.roster))
            }

            is Inbound.OnlineRemove -> {
                presence.remove(frame.userid)
                emit(SessionEvent.UserLeft(channel, frame.userid, frame.nick))
                emit(SessionEvent.RosterChanged(channel, presence.roster))
            }

            is Inbound.UpdateUser -> {
                presence.update(frame)
                emit(SessionEvent.RosterChanged(channel, presence.roster))
            }

            is Inbound.Chat -> emit(SessionEvent.Message(channel, frame))
            is Inbound.Emote -> emit(SessionEvent.Emote(channel, frame))
            is Inbound.Whisper -> emit(SessionEvent.Whisper(channel, frame))
            is Inbound.Invite -> emit(SessionEvent.Invited(channel, frame))
            is Inbound.UpdateMessage -> emit(SessionEvent.MessageUpdated(channel, frame))
            is Inbound.Info -> emit(SessionEvent.Notice(channel, frame))

            is Inbound.Warn -> {
                // Our local rate model was optimistic; resync before we make it worse.
                if (frame.id == ErrorId.RATELIMIT) governor.penalize(RateGovernor.Cost.JOIN)
                emit(SessionEvent.Warning(channel, frame))
            }

            is Inbound.Session -> if (frame.token.isNotEmpty()) tokenStore.save(url, channel, frame.token)
            is Inbound.Unknown -> emit(SessionEvent.UnknownFrame(channel, frame))
        }
    }

    private suspend fun emit(event: SessionEvent) = _events.emit(event)

    private fun costOf(frame: Outbound): Double = when (frame) {
        is Outbound.Chat -> RateGovernor.Cost.chat(frame.text)
        is Outbound.Whisper -> RateGovernor.Cost.chat(frame.text)
        is Outbound.Join -> RateGovernor.Cost.JOIN
        is Outbound.Invite -> RateGovernor.Cost.INVITE
        is Outbound.Session -> RateGovernor.Cost.SESSION
        else -> RateGovernor.Cost.DEFAULT
    }
}

private fun Inbound.Warn.isJoinFailure(): Boolean =
    id == ErrorId.JOIN_ALREADY_JOINED || text.contains("may not join") || text.contains("Nickname")

class FatalSessionException(message: String) : Exception(message)

/** The frame would be silently discarded by the server; refused client-side. */
class InvalidFrameException(message: String) : Exception(message)
