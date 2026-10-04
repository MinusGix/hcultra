package chat.hc.core.phantom

import chat.hc.core.net.Connection
import chat.hc.core.net.KtorTransport
import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import chat.hc.core.session.Backoff
import chat.hc.core.session.ChannelSession
import chat.hc.core.session.Credentials
import chat.hc.core.session.InMemoryTokenStore
import chat.hc.core.session.SessionEvent
import chat.hc.core.session.SessionState
import chat.hc.core.session.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient as JdkHttp
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * The phantom server: upstream hack.chat run locally by probe/phantom/phantom.mjs,
 * with an admin we control. `./gradlew :core:phantomTest` starts it and passes
 * its addresses in; these tests are skipped by plain `jvmTest`.
 */
object Phantom {
    val url: String = System.getenv("PHANTOM_URL") ?: "ws://127.0.0.1:6070"
    private val control: String = System.getenv("PHANTOM_CONTROL") ?: "http://127.0.0.1:6079"

    /** The global moderator configured at setup; see phantom.mjs. */
    const val MOD_PASS = "phantom-mod"

    private val http = JdkHttp.newHttpClient()
    private val ktor by lazy { KtorTransport.defaultClient(HttpClient(CIO)) }
    val transport: Transport by lazy { Logging(KtorTransport(ktor)) }

    /**
     * Every frame in and out, on stdout — which Gradle keeps per test in the
     * report, so a failure comes with the conversation that led to it.
     */
    private class Logging(private val inner: Transport) : Transport {
        private val start = System.currentTimeMillis()
        private val ids = AtomicInteger()

        override suspend fun open(url: String): Connection {
            val id = ids.incrementAndGet()
            log(id, "open $url")
            val c = inner.open(url)
            return object : Connection by c {
                override val incoming = c.incoming
                    .onEach { log(id, "<- $it") }
                    .onCompletion { log(id, "closed (${it?.message ?: "normally"})") }

                override suspend fun send(text: String) {
                    log(id, "-> $text")
                    c.send(text)
                }
            }
        }

        private fun log(id: Int, text: String) =
            println("%6d #%d %s".format(System.currentTimeMillis() - start, id, text.take(300)))
    }

    /** A channel no other test touches; the server keeps state per channel. */
    fun channel(prefix: String) = "$prefix-${Random.nextInt(100000, 999999)}"

    fun nick(prefix: String) = "$prefix${Random.nextInt(1000, 9999)}"

    /** Runs an admin action in [channel]; returns the frames the admin saw in reply. */
    fun admin(channel: String, verb: String, vararg args: Pair<String, Any>): List<JsonObject> {
        val body = JsonObject(
            mapOf("channel" to JsonPrimitive(channel), "verb" to JsonPrimitive(verb)) +
                args.associate { (k, v) -> k to v.toJson() }
        )
        val reply = post("/admin", body)
        return reply["frames"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    /** Silences the newest [n] client connections without closing them server-side. */
    fun freeze(n: Int = 1) = post("/freeze", JsonObject(mapOf("n" to JsonPrimitive(n))))

    /** Closes frozen connections, so the server finally lets their ghosts go. */
    fun release() = post("/release", JsonObject(emptyMap()))

    private fun post(path: String, body: JsonObject): JsonObject {
        val req = HttpRequest.newBuilder(URI.create("$control$path"))
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        val json = Json.parseToJsonElement(res.body()).jsonObject
        check(res.statusCode() == 200) { "phantom $path: ${res.statusCode()} $json" }
        return json
    }

    private fun Any.toJson(): JsonElement = when (this) {
        is Number -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(this)
        else -> JsonPrimitive(toString())
    }

    /**
     * A session on the phantom with fast backoff — the real one waits a minute
     * at its cap, which no test should — and every event recorded.
     *
     * [device] picks the address the server sees (see `withAddress` in
     * phantom.mjs). It defaults to the nick, so distinct users have distinct
     * hashes and rate budgets; reuse one to model the same phone reconnecting.
     */
    fun session(
        scope: CoroutineScope,
        channel: String,
        credentials: Credentials,
        device: String = credentials.nick,
        tokenStore: TokenStore = InMemoryTokenStore(),
    ): Recorded {
        val s = ChannelSession(
            channel = channel,
            credentials = credentials,
            url = "$url/?as=$device",
            transport = transport,
            governor = RateGovernor(),
            tokenStore = tokenStore,
            backoff = Backoff(baseMillis = 200, maxMillis = 1_000),
        )
        val events = Collections.synchronizedList(mutableListOf<SessionEvent>())
        val states = Collections.synchronizedList(mutableListOf<SessionState>())
        scope.launch { s.events.collect { events += it } }
        scope.launch { s.state.collect { states += it } }
        s.start(scope)
        return Recorded(s, events, states)
    }
}

class Recorded(
    val session: ChannelSession,
    val events: MutableList<SessionEvent>,
    val states: MutableList<SessionState>,
) {
    suspend fun awaitState(timeoutMillis: Long = 5_000, label: String = "state", pred: (SessionState) -> Boolean): SessionState =
        try {
            withTimeout(timeoutMillis) { session.state.first(pred) }
        } catch (e: Exception) {
            throw AssertionError("$label not reached; went ${snapshot(states)}", e)
        }

    suspend fun awaitLive(timeoutMillis: Long = 5_000) =
        awaitState(timeoutMillis, "Live") { it is SessionState.Live }

    inline fun <reified T : SessionEvent> seen(): List<T> = snapshot(events).filterIsInstance<T>()

    fun <T> snapshot(list: MutableList<T>): List<T> = synchronized(list) { list.toList() }
}
