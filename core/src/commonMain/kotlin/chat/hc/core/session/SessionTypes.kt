package chat.hc.core.session

import chat.hc.core.protocol.Inbound
import chat.hc.core.protocol.User

/** Credentials for one channel. `pass` is what produces the trip. */
data class Credentials(
    val nick: String,
    val pass: String? = null,
)

/**
 * Persisted session tokens, keyed by **server, channel and identity**.
 *
 * A token is only meaningful to the server that issued it, so the server is
 * part of the key rather than a global namespace. That also means trying a
 * different server does not cost you silent resume on the one you normally use
 * — and losing it would be worse than an inconvenience, since a cold rejoin is
 * visible to the whole channel as a leave/join pair.
 *
 * The [Credentials] are part of the key because a token *is* an identity: the
 * server restores the nick, trip and level it was issued for and ignores
 * whatever `join` would have said. Keyed by channel alone, presenting the
 * stored token silently reinstates whoever you were here last time — so typing
 * a different nick into the join form changed nothing, which is exactly the bug
 * this key shape prevents. A token that does not match the identity being asked
 * for is simply not offered, and the session cold-joins as asked instead.
 *
 * Tokens are JWTs valid 7 days, reissued on both join and restore — so a client
 * that connects at least weekly keeps a rolling window indefinitely. They grant
 * the holder our nick, trip and level: on Android this must be backed by the
 * Keystore, not plain preferences.
 */
interface TokenStore {
    suspend fun load(server: String, channel: String, identity: Credentials): String?
    suspend fun save(server: String, channel: String, identity: Credentials, token: String)
    suspend fun clear(server: String, channel: String, identity: Credentials)
}

/** In-memory store; the default for tests and for ephemeral-by-default mode. */
class InMemoryTokenStore : TokenStore {
    private val tokens = mutableMapOf<Triple<String, String, Credentials>, String>()
    override suspend fun load(server: String, channel: String, identity: Credentials): String? =
        tokens[Triple(server, channel, identity)]
    override suspend fun save(server: String, channel: String, identity: Credentials, token: String) {
        tokens[Triple(server, channel, identity)] = token
    }
    override suspend fun clear(server: String, channel: String, identity: Credentials) {
        tokens.remove(Triple(server, channel, identity))
    }
}

sealed interface SessionState {
    data object Idle : SessionState
    data object Connecting : SessionState
    data object Handshaking : SessionState
    data class Live(val restored: Boolean) : SessionState
    data class Reconnecting(val attempt: Int, val delayMillis: Long) : SessionState
    /** Terminal: retrying cannot help (e.g. nick rejected, channel locked). */
    data class Failed(val reason: String) : SessionState
}

/** Why a connection is being replaced. Drives make-before-break vs cold restore. */
enum class HandoffReason {
    /** Connection is gone; nothing to overlap with. Peers will see leave/join. */
    Dropped,
    /** Connection is still alive; overlap so peers see only `updateUser`. */
    NetworkChanged,
    TokenRefresh,
}

sealed interface SessionEvent {
    val channel: String

    data class Message(override val channel: String, val frame: Inbound.Chat) : SessionEvent
    data class Emote(override val channel: String, val frame: Inbound.Emote) : SessionEvent
    data class Whisper(override val channel: String, val frame: Inbound.Whisper) : SessionEvent
    data class Invited(override val channel: String, val frame: Inbound.Invite) : SessionEvent
    data class MessageUpdated(override val channel: String, val frame: Inbound.UpdateMessage) : SessionEvent
    data class Notice(override val channel: String, val frame: Inbound.Info) : SessionEvent
    data class Warning(override val channel: String, val frame: Inbound.Warn) : SessionEvent

    data class RosterChanged(override val channel: String, val roster: List<User>) : SessionEvent
    data class UserJoined(override val channel: String, val user: User) : SessionEvent
    data class UserLeft(override val channel: String, val userid: Long, val nick: String) : SessionEvent

    /**
     * Connection re-established. [silent] is true when peers saw only an
     * `updateUser` rather than a leave/join pair — i.e. we managed a
     * make-before-break handoff.
     */
    data class Resumed(override val channel: String, val restored: Boolean, val silent: Boolean) : SessionEvent

    data class Disconnected(override val channel: String, val cause: String?) : SessionEvent

    /**
     * A frame we do not model. Surfaced rather than swallowed so the UI can
     * show *something* and so we notice new server commands.
     */
    data class UnknownFrame(override val channel: String, val frame: Inbound.Unknown) : SessionEvent
}

/**
 * Reconnect backoff.
 *
 * Deliberately not aggressive: `join` costs 3 against a threshold of 25 with a
 * 30s halflife, shared across every session on this device, so a tight retry
 * loop across several channels will rate-limit us into a worse state than the
 * disconnect itself.
 */
class Backoff(
    private val baseMillis: Long = 1_000,
    private val maxMillis: Long = 60_000,
    private val jitter: () -> Double = { 0.5 },
) {
    fun delayFor(attempt: Int): Long {
        val exp = baseMillis shl (attempt - 1).coerceIn(0, 16)
        val capped = exp.coerceAtMost(maxMillis)
        // Full jitter across [capped/2, capped]: several channels drop together
        // when the network does, and they must not retry in lockstep.
        return (capped / 2 + (capped / 2 * jitter())).toLong().coerceAtLeast(baseMillis)
    }
}
