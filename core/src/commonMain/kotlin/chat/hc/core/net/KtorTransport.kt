package chat.hc.core.net

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Ktor-backed transport. The engine is supplied per platform (CIO on JVM/Android,
 * Darwin on iOS) so this stays in commonMain.
 */
class KtorTransport(private val client: HttpClient) : Transport {

    override suspend fun open(url: String): Connection =
        KtorConnection(client.webSocketSession(url))

    companion object {
        /** Ping keeps NAT and carrier middleboxes from silently dropping an idle socket. */
        fun defaultClient(engineClient: HttpClient): HttpClient = engineClient.config {
            install(WebSockets) {
                pingIntervalMillis = 20_000
            }
        }
    }
}

private class KtorConnection(
    private val session: DefaultClientWebSocketSession,
) : Connection {

    override val isOpen: Boolean get() = session.isActive

    override val incoming: Flow<String> = flow {
        for (frame in session.incoming) {
            if (frame is Frame.Text) emit(frame.readText())
        }
    }

    override suspend fun send(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        runCatching { session.close() }
    }
}

private val DefaultClientWebSocketSession.isActive: Boolean
    get() = !incoming.isClosedForReceive
