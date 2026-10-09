package chat.hc.core.session

import chat.hc.core.FakeTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Channels on more than one server at once. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerServersTest {

    private val other = "ws://old.example:6060"

    /** Says which server answered, so the two transcripts can be told apart. */
    private fun FakeTransport.scriptBoth() {
        onSend = { raw ->
            when (Json.parseToJsonElement(raw).jsonObject["cmd"]?.jsonPrimitive?.content) {
                "session" -> if (url == TEST_URL) {
                    serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                }
                "join" -> if (url == TEST_URL) {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"info","text":"from hack.chat","channel":"testroom"}""")
                } else {
                    serverSends("""{"cmd":"onlineSet","nicks":["tester"]}""")
                    serverSends("""{"cmd":"info","text":"from the old one"}""")
                }
            }
        }
    }

    private fun TestScope.manager(transport: FakeTransport) = SessionManager(
        scope = this,
        transport = transport,
        now = { 0L },
        customIdFactory = { "abc123" },
        dialectFor = { if (it == other) Dialect.V1 else Dialect.V2 },
    )

    @Test
    fun theSameChannelOnTwoServersIsTwoChannels() = runTest {
        val transport = FakeTransport().apply { scriptBoth() }
        val sessions = manager(transport)
        val here = key("testroom")
        val there = ChannelKey(other, "testroom")
        sessions.join(here, Credentials("tester"))
        sessions.join(there, Credentials("tester"))
        advanceUntilIdle()

        assertEquals(setOf(here, there), sessions.channels.value.keys)
        assertEquals(listOf(TEST_URL, other), transport.connections.map { it.url })
        // Each server's dialect, not one for all: the old one was never sent `session`.
        assertTrue(transport.connections[1].sent.none { "\"session\"" in it })
        fun said(k: ChannelKey) = sessions.channels.value.getValue(k).messages.map { it.text }
        assertTrue("from hack.chat" in said(here) && "from the old one" !in said(here))
        assertTrue("from the old one" in said(there) && "from hack.chat" !in said(there))
        sessions.stopAll()
    }

    @Test
    fun forgettingAServerLeavesOnlyItsChannels() = runTest {
        val transport = FakeTransport().apply { scriptBoth() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("tester"))
        sessions.join(ChannelKey(other, "testroom"), Credentials("tester"))
        sessions.join(ChannelKey(other, "elsewhere"), Credentials("tester"))
        advanceUntilIdle()

        sessions.forgetServer(other)
        advanceUntilIdle()

        assertEquals(setOf(key("testroom")), sessions.channels.value.keys)
        assertTrue(!transport.connections[1].isOpen && !transport.connections[2].isOpen)
        assertTrue(transport.connections[0].isOpen)
        sessions.stopAll()
    }
}
