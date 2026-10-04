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
import kotlinx.coroutines.channels.Channel
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
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout

/**
 * One channel, one socket.
 *
 * Since the 2026-10 update the server accepts a second `join` on a v2 socket
 * (legacy sockets get warn 35), keeping the first join's nick and password for
 * every channel. We still hold one socket per channel: it lets each tab carry
 * its own identity, and a drop costs one channel rather than all of them. The
 * session token already carries a `channels` array and `session.js` restores
 * every entry, so moving to one socket would not disturb the layers above.
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
 *  4. A token restore **overrides the `join` that would have followed** — the
 *     server reinstates the identity the token was issued for and never reads
 *     our nick. So the token is looked up by [credentials] as well as channel
 *     ([TokenStore]): asking to join as someone else must not be answered by
 *     silently becoming who we were here last time.
 *  5. A channel with a **captcha or password holds the join** and sends a
 *     challenge instead of `onlineSet`; the answer is a `chat`, and the server
 *     re-runs the join itself. A token restore into such a channel is refused
 *     outright, so every reconnect cold-joins and is challenged again.
 */
class ChannelSession(
    val channel: String,
    /** Public so [SessionManager] can tell a re-join from a change of identity. */
    val credentials: Credentials,
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

    /** Whether this session has ever been admitted — what makes a NAME_TAKEN our own ghost. */
    private var everLive = false

    /**
     * Backoff spent so far waiting for our previous socket to let go of the
     * nick. Counted from the delays we chose rather than a clock, so it means
     * the same under any [Backoff] and under a test's virtual time.
     */
    private var ghostWaitedMillis = 0L

    /** Whether the last attempt failed on our own ghost; set by [supervise]. */
    private var lastFailureWasGhost = false
    val roster get() = presence.roster

    /** Our own userid, learned at join and stable across restores. */
    var userid: Long? = null
        private set

    private var current: Connection? = null
    private var supervisor: Job? = null
    private var stopped = false
    /** Set when the next resume is expected to be invisible to peers. */
    private var expectSilentResume = false

    /** What the user typed for the current [SessionState.Challenged]. */
    private val answers = Channel<String>(Channel.CONFLATED)
    private var challengeSerial = 0

    /**
     * The channel password that last got us in. A token restore is refused for
     * a password-protected channel, so without this every dropped connection
     * would stop and ask again for something we already know. Memory only, and
     * forgotten the moment the server rejects it.
     */
    private var channelPassword: String? = null

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
        send(Outbound.Chat(channel, text, customId))

    /** Answers the challenge in [SessionState.Challenged]; ignored at any other time. */
    fun answer(text: String) {
        answers.trySend(text)
    }

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
        // Not interactive: the old socket is still serving the channel, and
        // stopping to ask the user a question would leave the tab showing a
        // prompt for a channel that is working fine. Keep the old one instead.
        val fresh = try {
            connectAndHandshake(scope, tokenStore.load(url, channel, credentials), interactive = false)
        } catch (_: ChallengeRequiredException) {
            expectSilentResume = false
            return
        } catch (_: GhostNickException) {
            // A channel that refuses token restore (password, captcha) forces a
            // cold join, whose collision check counts the very socket we are
            // replacing. Overlap is impossible there; keep the old one.
            expectSilentResume = false
            return
        }
        current = fresh.connection
        old.close()
        emit(SessionEvent.Resumed(channel, fresh.restored, silent = true))
        flushDeferred(fresh)
        pump(fresh)
    }

    private suspend fun supervise(scope: CoroutineScope) {
        var attempt = 0
        while (scope.isActive && !stopped) {
            try {
                _state.value = SessionState.Connecting
                val live = connectAndHandshake(scope, tokenStore.load(url, channel, credentials))
                current = live.connection
                attempt = 0
                everLive = true
                ghostWaitedMillis = 0
                _state.value = SessionState.Live(live.restored)
                emit(SessionEvent.Resumed(channel, live.restored, silent = expectSilentResume))
                expectSilentResume = false
                flushDeferred(live)
                pump(live)
                emit(SessionEvent.Disconnected(channel, null))
            } catch (c: CancellationException) {
                throw c
            } catch (e: FatalSessionException) {
                _state.value = SessionState.Failed(e.message ?: "fatal")
                emit(SessionEvent.Disconnected(channel, e.message))
                return
            } catch (e: GhostNickException) {
                lastFailureWasGhost = true
                emit(SessionEvent.Disconnected(channel, e.message))
            } catch (e: Exception) {
                emit(SessionEvent.Disconnected(channel, e.message))
            }

            current = null
            if (stopped) break

            attempt += 1
            val wait = backoff.delayFor(attempt)
            if (lastFailureWasGhost) ghostWaitedMillis += wait
            lastFailureWasGhost = false
            _state.value = SessionState.Reconnecting(attempt, wait)
            delay(wait)
        }
    }

    private class LiveConnection(
        val connection: Connection,
        val frames: ReceiveChannel<Inbound>,
        val restored: Boolean,
        /**
         * Frames that arrived mid-handshake, held back rather than dispatched.
         *
         * They must not be dropped — the MOTD, peer joins and even chat land in
         * this window — but dispatching them as they arrive puts them *above*
         * the resume notice, since that is only emitted once the handshake
         * returns. Holding them lets the notice keep its place at the seam.
         */
        val deferred: List<Inbound>,
    )

    private suspend fun connectAndHandshake(
        scope: CoroutineScope,
        token: String?,
        /** False when a challenge should fail the handshake instead of waiting on the user. */
        interactive: Boolean = true,
    ): LiveConnection {
        val deferred = mutableListOf<Inbound>()
        var conn: Connection? = null
        try {
            _state.value = SessionState.Handshaking
            val opened = transport.open(url)
            conn = opened
            val frames = opened.incoming.map(FrameCodec::decode).produceIn(scope)

            // Frame 1, always: declares protocol v2 whether or not we hold a token.
            governor.spend(RateGovernor.Cost.SESSION)
            opened.send(FrameCodec.encode(Outbound.Session(token)))

            val session = when (val reply = awaitFrame(frames, "session reply", deferred) {
                it is Inbound.Session || (it is Inbound.Warn && it.isRateLimited())
            }) {
                is Inbound.Session -> reply
                // Blocked before the handshake even starts: the reply to
                // `session` is replaced by the warn. Back off like a refused join.
                else -> {
                    deferred += reply
                    throw JoinRateLimitedException()
                }
            }
            if (session.token.isNotEmpty()) tokenStore.save(url, channel, credentials, session.token)

            // session.js restores each channel *before* it replies, so `channels`
            // is the server's verdict: a channel missing from it was refused —
            // captcha, password, lock or a nick collision — and the warn saying
            // so is already among the deferred frames, where it will explain
            // what happens next in the transcript. A cold join on this same
            // socket is how to get the challenge.
            if (session.restored && channel in session.channels) {
                // Usually already here for the same reason; waited for only in
                // case a server ever replies first.
                val set = deferred.firstOrNull { it is Inbound.OnlineSet }?.also { deferred.remove(it) }
                    ?: runCatching {
                        awaitFrame(frames, "restored onlineSet", deferred) { it is Inbound.OnlineSet }
                    }.getOrNull()
                // Dispatched immediately, not deferred: the roster and our own
                // userid have to be current before any held-back chat is
                // replayed through it.
                set?.let { dispatch(it) }
                return LiveConnection(opened, frames, restored = true, deferred = deferred.toList())
            }

            governor.spend(RateGovernor.Cost.JOIN)
            opened.send(FrameCodec.encode(Outbound.Join(channel, credentials.nick, credentials.pass)))

            dispatch(awaitAdmission(opened, frames, deferred, interactive))

            // The token trails onlineSet and the MOTD. Missing it is not fatal —
            // we simply lose silent-resume until the next token arrives.
            try {
                withTimeout(tokenGraceMillis) {
                    val tok = awaitFrame(frames, "post-join token", deferred) {
                        it is Inbound.Session && it.token.isNotEmpty()
                    } as Inbound.Session
                    tokenStore.save(url, channel, credentials, tok.token)
                }
            } catch (_: TimeoutCancellationException) {
                // Leave the old token in place; a later frame may still carry one.
            }

            return LiveConnection(opened, frames, restored = false, deferred = deferred.toList())
        } catch (c: CancellationException) {
            conn?.close()
            throw c
        } catch (e: Throwable) {
            conn?.close()
            // The handshake failed, so no resume notice will ever be emitted and
            // there is no seam left to order against. Deliver what we collected
            // rather than losing it — a join refusal in particular is usually
            // preceded by the info frames explaining why.
            deferred.forEach { dispatch(it) }
            throw e
        }
    }

    /**
     * Waits out a sent `join` until the server lets us in, answering whatever it
     * asks on the way. Returns the `onlineSet`.
     *
     * Challenges can chain — captcha is checked before password (hook priority
     * 5 vs 6), so a channel with both asks twice. A wrong answer spends the
     * challenge without issuing another, so the join is re-sent to get a fresh
     * one rather than leaving the user typing into nothing.
     */
    private suspend fun awaitAdmission(
        conn: Connection,
        frames: ReceiveChannel<Inbound>,
        deferred: MutableList<Inbound>,
        interactive: Boolean,
    ): Inbound.OnlineSet {
        var retry = false
        // The remembered password gets one try per join; if it is refused the
        // channel's password changed, and only the user can supply the new one.
        var triedRemembered = false
        // Whether the answer in flight came from memory rather than the user.
        var answeredForUser = false
        var offeredPassword: String? = null

        while (true) {
            val reply = awaitFrame(frames, "onlineSet", deferred) {
                it is Inbound.OnlineSet || it is Inbound.Captcha || it is Inbound.PasswordReq ||
                    (it is Inbound.Warn && (it.isJoinFailure() || it.isWrongAnswer() || it.isRateLimited()))
            }
            when (reply) {
                is Inbound.OnlineSet -> {
                    // A lock turns us away without a warn: join.js rewrites the
                    // join to ?purgatory under a random nick and lets *that*
                    // succeed. Its onlineSet is the only sign. Retrying cannot
                    // help until someone unlocks, so this is terminal.
                    if (reply.channel != null && reply.channel != channel) {
                        throw FatalSessionException("?$channel is locked")
                    }
                    offeredPassword?.let { channelPassword = it }
                    return reply
                }

                is Inbound.Warn -> {
                    // join.js reports its own rate limit as Global.RATELIMIT
                    // since the 2026-10 update, not from the Join block, and an
                    // address over the threshold gets BLOCKED for any frame.
                    // Not fatal: hand it back as deferred so dispatch() both
                    // shows it and resyncs the governor, and let supervise() back off.
                    if (reply.isRateLimited()) {
                        deferred += reply
                        throw JoinRateLimitedException()
                    }
                    // Token restore is refused into password and captcha
                    // channels, and the cold join that follows checks nick
                    // collisions without excluding our own userid — so a
                    // reconnect collides with the socket we just lost until the
                    // server reaps it. A first join has no ghost: that clash is real.
                    if (reply.id == ErrorId.JOIN_NAME_TAKEN && everLive && ghostWaitedMillis < GHOST_WINDOW_MILLIS) {
                        throw GhostNickException()
                    }
                    if (!reply.isWrongAnswer()) {
                        throw FatalSessionException("join refused (id ${reply.id}): ${reply.text}")
                    }
                    // Mirrors the server's frisk(socket, 7) for a wrong answer.
                    governor.penalize(WRONG_ANSWER_COST)
                    if (reply.id == ErrorId.INVALID_PASSWORD) channelPassword = null
                    // A refused remembered password is not the user's mistake:
                    // they have not been asked anything yet.
                    retry = !answeredForUser
                    answeredForUser = false
                    offeredPassword = null
                    governor.spend(RateGovernor.Cost.JOIN)
                    conn.send(FrameCodec.encode(Outbound.Join(channel, credentials.nick, credentials.pass)))
                }

                is Inbound.Captcha -> {
                    val answer = awaitAnswer(frames, deferred, Challenge.Captcha(reply.text, retry), interactive)
                    retry = false
                    answeredForUser = false
                    sendAnswer(conn, answer)
                }

                is Inbound.PasswordReq -> {
                    val remembered = channelPassword
                    answeredForUser = remembered != null && !triedRemembered
                    val answer = if (answeredForUser) {
                        triedRemembered = true
                        remembered!!
                    } else {
                        awaitAnswer(frames, deferred, Challenge.Password(retry), interactive)
                    }
                    retry = false
                    offeredPassword = answer
                    sendAnswer(conn, answer)
                }

                else -> error("unreachable: $reply")
            }
        }
    }

    private suspend fun sendAnswer(conn: Connection, text: String) {
        governor.spend(RateGovernor.Cost.chat(text))
        conn.send(FrameCodec.encode(Outbound.Chat(channel, text)))
    }

    /**
     * Shows [challenge] and suspends until the user answers it.
     *
     * Deliberately outside the handshake timeout — a person reading ASCII art
     * on a phone takes longer than any timeout that also catches a dead socket.
     * The socket is watched instead: if it closes while we wait, that ends the
     * wait the same way any other handshake failure would.
     */
    private suspend fun awaitAnswer(
        frames: ReceiveChannel<Inbound>,
        deferred: MutableList<Inbound>,
        challenge: Challenge,
        interactive: Boolean,
    ): String {
        if (!interactive) throw ChallengeRequiredException()
        // Anything typed before this challenge was shown answers an older one.
        while (answers.tryReceive().isSuccess) Unit
        _state.value = SessionState.Challenged(challenge, ++challengeSerial)
        while (true) {
            val answer = select<String?> {
                answers.onReceive { it }
                frames.onReceiveCatching { result ->
                    val frame = result.getOrNull()
                        ?: throw ConnectionClosedException("$channel: closed while awaiting an answer")
                    deferred += frame
                    null
                }
            }
            // Only on this path: a failure leaves the state to supervise(), and
            // a stop() sets Idle — a reset in a finally could land after it.
            if (answer != null) {
                _state.value = SessionState.Handshaking
                return answer
            }
        }
    }

    /** Reads until the connection closes. */
    private suspend fun pump(live: LiveConnection) {
        for (frame in live.frames) dispatch(frame)
    }

    /** Replays the mid-handshake frames, once the resume notice is in place. */
    private suspend fun flushDeferred(live: LiveConnection) {
        live.deferred.forEach { dispatch(it) }
    }

    /**
     * Waits for a matching frame, collecting everything else into [deferred] on
     * the way — the MOTD, peer joins and even chat can arrive mid-handshake and
     * must not be dropped on the floor.
     */
    private suspend fun awaitFrame(
        frames: ReceiveChannel<Inbound>,
        label: String,
        deferred: MutableList<Inbound>,
        predicate: (Inbound) -> Boolean,
    ): Inbound = withTimeout(handshakeTimeoutMillis) {
        for (frame in frames) {
            if (predicate(frame)) return@withTimeout frame
            deferred += frame
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

            // `chat` carries only the level's default flair: upstream's chat.js
            // builds it from getAppearance(level) and ignores the per-channel
            // override `forceflair` stores. The roster comes from
            // getUserDetails(), which applies it, so prefer that — it is what
            // the site's own client renders from too.
            is Inbound.Chat -> {
                val flair = presence[frame.userid]?.flair ?: frame.flair
                emit(SessionEvent.Message(channel, frame.copy(flair = flair)))
            }
            is Inbound.Emote -> emit(SessionEvent.Emote(channel, frame))
            is Inbound.Whisper -> emit(SessionEvent.Whisper(channel, frame))
            is Inbound.Invite -> emit(SessionEvent.Invited(channel, frame))
            is Inbound.UpdateMessage -> emit(SessionEvent.MessageUpdated(channel, frame))
            is Inbound.Info -> emit(SessionEvent.Notice(channel, frame))

            is Inbound.Warn -> {
                // Our local rate model was optimistic; resync before we make it worse.
                if (frame.isRateLimited()) governor.penalize(RateGovernor.Cost.JOIN)
                emit(SessionEvent.Warning(channel, frame))
            }

            is Inbound.Session ->
                if (frame.token.isNotEmpty()) tokenStore.save(url, channel, credentials, frame.token)
            // Only meaningful mid-join, where awaitAdmission consumes them.
            is Inbound.Captcha, is Inbound.PasswordReq -> Unit
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

/**
 * The Join block (31–35) by id. "may not join" carries a canJoinChannel reason
 * code from the Channel block instead, so it is matched on text.
 */
private fun Inbound.Warn.isJoinFailure(): Boolean =
    id in ErrorId.JOIN_INVALID_NICK..ErrorId.JOIN_LEGACY_RESTRICT || text.contains("may not join")

/** A command's own rate limit, or the server refusing every frame from our address. */
private fun Inbound.Warn.isRateLimited(): Boolean =
    id == ErrorId.RATELIMIT || id == ErrorId.BLOCKED

private fun Inbound.Warn.isWrongAnswer(): Boolean =
    id == ErrorId.BAD_CAPTCHA || id == ErrorId.INVALID_PASSWORD

/**
 * How long to keep waiting for the server to drop our previous socket.
 *
 * hackchat-server pings every 16 s but never acts on a missing pong, and
 * hack.chat is plain nginx on one host with no CDN in front, so a phone that
 * vanished is only noticed when the server's TCP gives up retransmitting:
 * about 15½ minutes at Linux's default `tcp_retries2`. Past this, the nick is
 * someone else's.
 */
private const val GHOST_WINDOW_MILLIS = 16 * 60_000L

/** What `frisk(socket, 7)` charges for a wrong captcha or password. */
private const val WRONG_ANSWER_COST = 7.0

class FatalSessionException(message: String) : Exception(message)

/** The server rate-limited our join, or blocked our address. Retryable; the backoff is the remedy. */
private class JoinRateLimitedException : Exception("join rate-limited")

/** A rejoin collided with our own not-yet-reaped previous socket. Retryable. */
private class GhostNickException : Exception("nick still held by the previous connection")

/** A non-interactive handshake was asked a question only the user can answer. */
private class ChallengeRequiredException : Exception("join requires a captcha or password")

/** The frame would be silently discarded by the server; refused client-side. */
class InvalidFrameException(message: String) : Exception(message)
