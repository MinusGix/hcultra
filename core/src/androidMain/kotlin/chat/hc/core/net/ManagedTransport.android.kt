package chat.hc.core.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/**
 * A transport plus the client that backs it, so callers can shut it down
 * without depending on Ktor themselves. The HTTP stack is core's business;
 * the app module should not have to know which engine we use.
 */
class ManagedTransport internal constructor(private val client: HttpClient) {
    val transport: Transport = KtorTransport(client)

    fun close() = client.close()

    companion object {
        fun create(): ManagedTransport =
            ManagedTransport(KtorTransport.defaultClient(HttpClient(CIO)))
    }
}
