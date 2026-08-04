package chat.hc.core.net

import kotlinx.coroutines.flow.Flow

/**
 * A single WebSocket connection, reduced to what the session layer needs.
 *
 * Kept as an interface so [chat.hc.core.session.ChannelSession] can be driven by
 * a fake in tests — the handshake ordering and reconnect rules are the parts
 * most likely to break, and they must be testable without a live server.
 */
interface Connection {
    /** Raw inbound text frames. Completes when the connection closes. */
    val incoming: Flow<String>

    suspend fun send(text: String)

    /** Graceful close. Safe to call more than once. */
    suspend fun close()

    val isOpen: Boolean
}

interface Transport {
    suspend fun open(url: String): Connection
}

class ConnectionClosedException(message: String, cause: Throwable? = null) : Exception(message, cause)
