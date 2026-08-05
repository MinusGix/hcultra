package chat.hc.core.smoke

import chat.hc.core.net.KtorTransport
import chat.hc.core.net.RateGovernor
import chat.hc.core.session.ChannelSession
import chat.hc.core.session.Credentials
import chat.hc.core.session.InMemoryTokenStore
import chat.hc.core.session.SessionEvent
import chat.hc.core.session.SessionState
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * End-to-end smoke test against the real server.
 *
 * The unit tests prove the session layer is consistent with our *model* of the
 * protocol; only this proves the model matches the server. Run with:
 *
 *     ./gradlew :core:liveSmoke
 *
 * Joins a random channel, so it never disturbs a real room. Exercises the two
 * paths that matter: cold join (capturing the token that trails onlineSet) and
 * token restore on a second socket.
 */
fun main() = runBlocking {
    val url = System.getenv("HC_URL") ?: "wss://hack.chat/chat-ws"
    val channel = "smoke-${Random.nextInt(100000, 999999)}"
    val nick = "smoke${Random.nextInt(1000, 9999)}"

    val client = KtorTransport.defaultClient(HttpClient(CIO))
    val transport = KtorTransport(client)
    val governor = RateGovernor()
    val store = InMemoryTokenStore()

    println("→ $url  channel=$channel nick=$nick")

    val session = ChannelSession(
        channel = channel,
        credentials = Credentials(nick),
        url = url,
        transport = transport,
        governor = governor,
        tokenStore = store,
    )

    val scope = CoroutineScope(coroutineContext + Job())
    scope.launch {
        session.events.collect { e ->
            when (e) {
                is SessionEvent.Message -> println("   chat  <${e.frame.nick}> ${e.frame.text}")
                is SessionEvent.Notice -> println("   info  ${e.frame.text.take(70)}")
                is SessionEvent.Warning -> println("   WARN  id=${e.frame.id} ${e.frame.text}")
                is SessionEvent.RosterChanged -> println("   roster ${e.roster.map { it.nick }}")
                is SessionEvent.Resumed -> println("   resumed(restored=${e.restored}, silent=${e.silent})")
                is SessionEvent.UnknownFrame -> println("   unknown cmd=${e.frame.cmd}")
                else -> Unit
            }
        }
    }

    session.start(scope)

    val live = withTimeoutOrNull(15_000) {
        while (session.state.value !is SessionState.Live) delay(100)
        session.state.value as SessionState.Live
    }
    check(live != null) { "never reached Live: ${session.state.value}" }
    println("✓ joined (restored=${live.restored}) userid=${session.userid}")

    val token = store.load(url, channel, Credentials(nick))
    check(!token.isNullOrEmpty()) { "no session token captured — the trailing-token wait is broken" }
    println("✓ captured trailing token (${token.length} chars)")

    session.sendChat("hcultra core smoke test")
    delay(1500)

    // Second socket restoring the same token: the make-before-break path.
    println("→ restoring on a second socket while the first is still open")
    val second = ChannelSession(
        channel = channel,
        credentials = Credentials(nick),
        url = url,
        transport = transport,
        governor = governor,
        tokenStore = store,
    )
    val scope2 = CoroutineScope(coroutineContext + Job())
    second.start(scope2)
    val live2 = withTimeoutOrNull(15_000) {
        while (second.state.value !is SessionState.Live) delay(100)
        second.state.value as SessionState.Live
    }
    check(live2 != null) { "restore never reached Live: ${second.state.value}" }
    check(live2.restored) { "server did not honour the token — restored=false" }
    println("✓ restored on second socket (no join sent)")

    second.sendChat("still here after handoff")
    delay(1500)

    session.stop()
    second.stop()
    scope.coroutineContext[Job]?.cancel()
    scope2.coroutineContext[Job]?.cancel()
    client.close()
    println("✓ smoke test passed")
}
