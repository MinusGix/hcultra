package chat.hc.core

import chat.hc.core.net.Connection
import chat.hc.core.net.Transport
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Scriptable transport. Tests drive the server side by hand so handshake
 * ordering and reconnect behaviour are verifiable without a live server.
 */
class FakeTransport : Transport {
    val connections = mutableListOf<FakeConnection>()

    /** Frames the fake server replies with, keyed by the command it is answering. */
    var onSend: suspend FakeConnection.(String) -> Unit = {}

    override suspend fun open(url: String): Connection {
        val c = FakeConnection(this)
        connections += c
        return c
    }

    val latest: FakeConnection get() = connections.last()
}

class FakeConnection(private val owner: FakeTransport) : Connection {
    private val inbox = Channel<String>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    private var open = true

    override val incoming: Flow<String> = inbox.consumeAsFlow()
    override val isOpen: Boolean get() = open

    override suspend fun send(text: String) {
        sent += text
        owner.onSend(this, text)
    }

    override suspend fun close() {
        if (open) {
            open = false
            inbox.close()
        }
    }

    /** Push a frame from the "server". */
    suspend fun serverSends(json: String) {
        inbox.send(json)
    }

    /** Simulate the OS killing the socket. */
    fun kill() {
        open = false
        inbox.close()
    }
}
