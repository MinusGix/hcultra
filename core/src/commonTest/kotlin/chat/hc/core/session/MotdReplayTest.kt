package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The MOTD across connections.
 *
 * Verified against live (probe): the server sends `info` id 1304 in reply to
 * `join` and to nothing else — a socket presenting a stored token is answered
 * with `onlineSet` alone. Since this client restores whenever it holds a token,
 * and the scrollback is ephemeral by default, the MOTD was visible exactly once
 * per channel and identity, ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MotdReplayTest {

    private val motd = "Hey, Americans: https://act.eff.org/action/x"

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    /** Cold join: onlineSet, then the MOTD, then the trailing token. Live order. */
    private fun FakeTransport.scriptColdJoin() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"alice","userid":99}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"info","text":"$motd","id":1304,"channel":"testroom"}""")
                    serverSends("""{"cmd":"session","restored":false,"token":"tok","channels":["testroom"]}""")
                }
            }
        }
    }

    /** Token restore: a roster and nothing else. No `join`, so no MOTD. */
    private fun FakeTransport.scriptRestore() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> {
                    serverSends("""{"cmd":"session","restored":true,"token":"tok","channels":["testroom"]}""")
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"alice","userid":99}],"channel":"testroom"}""")
                }
            }
        }
    }

    private fun manager(
        transport: FakeTransport,
        scope: CoroutineScope,
        tokens: TokenStore,
        motds: MotdStore,
    ) = SessionManager(
        scope = scope,
        initialUrl = TEST_URL,
        transport = transport,
        tokenStore = tokens,
        motdStore = motds,
        now = { 0L },
        customIdFactory = { "abc123" },
    )

    private fun infoTexts(sessions: SessionManager) =
        sessions.channels.value["testroom"]!!.messages
            .filter { it.kind == MessageKind.Info }
            .map { it.text }

    @Test
    fun aColdJoinShowsTheMotdItWasSent() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, InMemoryTokenStore(), InMemoryMotdStore())
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        assertEquals(listOf("Users online: alice", motd), infoTexts(sessions))
        sessions.stopAll()
    }

    /**
     * The reported bug: every launch after the first restores a token, is sent
     * no MOTD, and — with the buffer ephemeral — showed nothing.
     */
    @Test
    fun aRestoredSessionShowsTheRememberedMotd() = runTest {
        val tokens = InMemoryTokenStore()
        val motds = InMemoryMotdStore()

        val first = FakeTransport().apply { scriptColdJoin() }
        val one = manager(first, this, tokens, motds)
        one.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        one.stopAll()
        advanceUntilIdle()

        // A new process: fresh manager and empty buffers, persistent stores.
        val second = FakeTransport().apply { scriptRestore() }
        val two = manager(second, this, tokens, motds)
        two.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        assertTrue(
            second.latest.sent.none { cmdOf(it) == "join" },
            "restored sessions must not cold-join",
        )
        assertEquals(motd, infoTexts(two).last(), "the remembered MOTD was not replayed")
        // Below the roster line, where a cold join puts it.
        assertTrue(infoTexts(two).indexOf("Users online: alice") < infoTexts(two).indexOf(motd))
        two.stopAll()
    }

    /** Nothing to remember yet: a first-ever restore must not invent a line. */
    @Test
    fun anEmptyStoreAddsNothing() = runTest {
        val transport = FakeTransport().apply { scriptRestore() }
        val sessions = manager(transport, this, InMemoryTokenStore(), InMemoryMotdStore())
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        assertEquals(listOf("Reconnected.", "Users online: alice"), infoTexts(sessions))
        sessions.stopAll()
    }

    /** A reconnect keeps the transcript, so the MOTD must not pile up. */
    @Test
    fun aReconnectDoesNotRepeatIt() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, InMemoryTokenStore(), InMemoryMotdStore())
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.scriptRestore()
        transport.latest.kill()
        advanceUntilIdle()

        assertEquals(1, infoTexts(sessions).count { it == motd }, "the MOTD was repeated")
        sessions.stopAll()
    }
}
