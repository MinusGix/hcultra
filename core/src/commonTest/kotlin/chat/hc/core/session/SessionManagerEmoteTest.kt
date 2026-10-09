package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Emotes are chat in the third person. They never counted as unread, and our
 * own were not marked as ours, since the initial commit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerEmoteTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    /** Us as `alice`/99, with `bob`/7 in the room. */
    private fun FakeTransport.scriptJoin() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(
                    """{"cmd":"onlineSet","users":[""" +
                        """{"isme":true,"nick":"alice","userid":99},""" +
                        """{"isme":false,"nick":"bob","userid":7}""" +
                        """],"channel":"testroom"}"""
                )
            }
        }
    }

    private fun manager(transport: FakeTransport, scope: kotlinx.coroutines.CoroutineScope) =
        SessionManager(
            scope = scope,
            transport = transport,
            now = { 0L },
            customIdFactory = { "abc123" },
        )

    @Test
    fun someoneElsesEmoteCountsAsUnread() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"emote","nick":"bob","userid":7,"channel":"testroom","text":"@bob waves"}"""
        )
        advanceUntilIdle()

        val ui = sessions.channels.value.getValue(key("testroom"))
        assertEquals(1, ui.unread)
        assertFalse(ui.messages.single { it.kind == MessageKind.Emote }.isMine)
        sessions.stopAll()
    }

    @Test
    fun ourOwnEmoteIsOursAndNotUnread() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"emote","nick":"alice","userid":99,"channel":"testroom","text":"@alice waves"}"""
        )
        advanceUntilIdle()

        val ui = sessions.channels.value.getValue(key("testroom"))
        assertEquals(0, ui.unread)
        assertTrue(ui.messages.single { it.kind == MessageKind.Emote }.isMine)
        sessions.stopAll()
    }

    /** The channel on screen never counts, for emotes as for chat. */
    @Test
    fun theChannelOnScreenDoesNotCount() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join(key("testroom"), Credentials("alice"))
        sessions.activeChannel = key("testroom")
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"emote","nick":"bob","userid":7,"channel":"testroom","text":"@bob waves"}"""
        )
        advanceUntilIdle()

        assertEquals(0, sessions.channels.value.getValue(key("testroom")).unread)
        sessions.stopAll()
    }
}
