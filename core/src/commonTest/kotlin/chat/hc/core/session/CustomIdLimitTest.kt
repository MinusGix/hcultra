package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.net.RateGovernor
import chat.hc.core.protocol.Limits
import chat.hc.core.protocol.Outbound
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Regression tests for a bug that cost real debugging time on the emulator:
 * a 12-character customId meant every message was discarded by the server with
 * no reply, while silently charging 13 of our 25 rate-limit points. The message
 * simply never appeared, and nothing anywhere reported an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CustomIdLimitTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    private fun liveSession(transport: FakeTransport): ChannelSession {
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"t","userid":9}],"channel":"c"}""")
                    serverSends("""{"cmd":"session","restored":false,"token":"tok","channels":["c"]}""")
                }
            }
        }
        return ChannelSession(
            channel = "c",
            credentials = Credentials("t"),
            url = "wss://example/chat-ws",
            transport = transport,
            governor = RateGovernor(),
        )
    }

    @Test
    fun serverLimitIsSix() {
        // Guard the constant itself: chat.js MAX_MESSAGE_ID_LENGTH.
        assertEquals(6, Limits.MAX_CUSTOM_ID_LENGTH)
    }

    @Test
    fun oversizedCustomIdIsRefusedBeforeItReachesTheSocket() = runTest {
        val transport = FakeTransport()
        val s = liveSession(transport)
        s.start(this)
        advanceUntilIdle()

        val before = transport.latest.sent.size
        assertFailsWith<InvalidFrameException> {
            s.send(Outbound.Chat("hi", customId = "abcdefghijkl"))
        }
        assertEquals(before, transport.latest.sent.size, "oversized frame must not be written")
        s.stop()
    }

    @Test
    fun updateMessageCustomIdIsAlsoChecked() = runTest {
        val transport = FakeTransport()
        val s = liveSession(transport)
        s.start(this)
        advanceUntilIdle()

        assertFailsWith<InvalidFrameException> {
            s.send(Outbound.UpdateMessage("append", "x", customId = "waytoolong"))
        }
        s.stop()
    }

    @Test
    fun sixCharCustomIdIsAccepted() = runTest {
        val transport = FakeTransport()
        val s = liveSession(transport)
        s.start(this)
        advanceUntilIdle()

        s.send(Outbound.Chat("hi", customId = "abc123"))
        val last = transport.latest.sent.last()
        assertEquals("chat", cmdOf(last))
        assertTrue(last.contains("abc123"))
        s.stop()
    }

    /** The generator the app actually uses must satisfy the limit, always. */
    @Test
    fun generatedCustomIdsFitTheLimit() {
        val factory = { seed: Long -> seed.toString(36).padStart(6, '0') }
        for (seed in listOf(0L, 1L, 60_466_175L, 2_176_782_335L)) {
            val id = factory(seed)
            assertTrue(
                id.length <= Limits.MAX_CUSTOM_ID_LENGTH,
                "generated customId '$id' is ${id.length} chars",
            )
        }
    }
}
