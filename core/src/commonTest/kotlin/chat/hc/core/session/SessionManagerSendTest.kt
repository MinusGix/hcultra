package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sending through the manager on a v2 connection: the optimistic bubble, and
 * every way it can end — echoed, never echoed, refused by the socket — plus
 * the slash command, which gets no bubble at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerSendTest {

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** Us as `alice`/99, with `bob`/7 in the room. Chat is echoed only by hand. */
    private fun FakeTransport.scriptJoin(refuseChat: Boolean = false) {
        onSend = { raw ->
            when (parse(raw).str("cmd")) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(
                    """{"cmd":"onlineSet","users":[""" +
                        """{"isme":true,"nick":"alice","userid":99},""" +
                        """{"isme":false,"nick":"bob","userid":7}""" +
                        """],"channel":"testroom"}"""
                )
                "chat" -> if (refuseChat) throw IllegalStateException("socket refused the write")
            }
        }
    }

    private fun TestScope.manager(transport: FakeTransport) = SessionManager(
        scope = this,
        transport = transport,
        now = { 0L },
        customIdFactory = { "abc123" },
    )

    private fun SessionManager.chats() =
        channels.value.getValue(key("testroom")).messages.filter { it.kind == MessageKind.Chat }

    private fun FakeTransport.sentChats() =
        latest.sent.map(::parse).filter { it.str("cmd") == "chat" }

    @Test
    fun ourMessageShowsAtOnceAndItsEchoReconcilesIt() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        val pending = sessions.chats().single()
        assertEquals(Delivery.Sending, pending.delivery)
        assertTrue(pending.isMine)
        assertEquals("alice", pending.nick, "named from the roster, not left blank")
        assertEquals(99, pending.userid)
        assertEquals("abc123", transport.sentChats().single().str("customId"))

        transport.latest.serverSends(
            """{"cmd":"chat","nick":"alice","userid":99,"channel":"testroom","text":"hello","customId":"abc123"}"""
        )
        advanceUntilIdle()

        val echoed = sessions.chats().single()
        assertEquals(Delivery.Sent, echoed.delivery)
        assertEquals(pending.localId, echoed.localId, "the bubble was replaced rather than reconciled")
        sessions.stopAll()
    }

    /** No echo ever comes late on hack.chat, so one that has not come is given up on. */
    @Test
    fun aMessageNeverEchoedBecomesUnconfirmed() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(Delivery.Sending, sessions.chats().single().delivery)

        advanceTimeBy(2)
        runCurrent()
        assertEquals(Delivery.Unconfirmed, sessions.chats().single().delivery)
        sessions.stopAll()
    }

    /** The echo landing first means the timeout that follows changes nothing. */
    @Test
    fun anEchoedMessageStaysSentPastTheTimeout() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        transport.latest.serverSends(
            """{"cmd":"chat","nick":"alice","userid":99,"channel":"testroom","text":"hello","customId":"abc123"}"""
        )
        advanceTimeBy(20_000)
        runCurrent()

        assertEquals(Delivery.Sent, sessions.chats().single().delivery)
        sessions.stopAll()
    }

    @Test
    fun aMessageTheSocketRefusesIsFailed() = runTest {
        val transport = FakeTransport().apply { scriptJoin(refuseChat = true) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        advanceUntilIdle()

        assertEquals(Delivery.Failed, sessions.chats().single().delivery)
        sessions.stopAll()
    }

    /**
     * A command is answered however the server likes, or not at all, so there
     * is no echo to wait for and a bubble would spin forever.
     */
    @Test
    fun aSlashCommandIsSentWithoutABubble() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "/me waves")
        advanceUntilIdle()

        val sent = transport.sentChats().single()
        assertEquals("/me waves", sent.str("text"))
        assertEquals("testroom", sent.str("channel"))
        assertNull(sent["customId"], "a command carries nothing to correlate")
        assertTrue(sessions.chats().isEmpty(), "a bubble was added: ${sessions.chats()}")
        sessions.stopAll()
    }

    /** A dropped socket will never echo; what was waiting stops waiting at once. */
    @Test
    fun aDropGivesUpOnEveryPendingMessage() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        runCurrent()
        transport.latest.kill()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(Delivery.Unconfirmed, sessions.chats().single().delivery)
        sessions.stopAll()
    }

    @Test
    fun sendingWhereWeHaveNotJoinedDoesNothing() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.sendChat(key("elsewhere"), "hello")
        advanceUntilIdle()

        assertTrue(transport.sentChats().isEmpty())
        assertTrue(key("elsewhere") !in sessions.channels.value)
        sessions.stopAll()
    }

    /** Looking at a channel is reading it: what was unread there no longer is. */
    @Test
    fun showingAChannelClearsItsUnread() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport)
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        transport.latest.serverSends("""{"cmd":"chat","nick":"bob","userid":7,"channel":"testroom","text":"one"}""")
        transport.latest.serverSends("""{"cmd":"chat","nick":"bob","userid":7,"channel":"testroom","text":"two"}""")
        advanceUntilIdle()
        assertEquals(2, sessions.channels.value.getValue(key("testroom")).unread)
        assertEquals(2, sessions.totalUnread)

        sessions.activeChannel = key("testroom")
        assertEquals(0, sessions.channels.value.getValue(key("testroom")).unread)
        assertEquals(0, sessions.totalUnread)
        sessions.stopAll()
    }
}
