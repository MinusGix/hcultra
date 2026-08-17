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
import kotlin.test.assertTrue

/**
 * "Join/left notify", the site's setting of the same name.
 *
 * Suppression happens before the buffer rather than at render time, so these
 * assert on what the manager kept, not on what a renderer would show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerJoinLeaveTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    private fun FakeTransport.scriptJoin() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(
                    """{"cmd":"onlineSet","users":[{"isme":true,"nick":"alice","userid":99}],"channel":"testroom"}"""
                )
            }
        }
    }

    private fun manager(
        transport: FakeTransport,
        scope: kotlinx.coroutines.CoroutineScope,
        showJoinLeave: () -> Boolean,
    ) = SessionManager(
        scope = scope,
        initialUrl = TEST_URL,
        transport = transport,
        showJoinLeave = showJoinLeave,
        now = { 0L },
        customIdFactory = { "abc123" },
    )

    private suspend fun FakeTransport.peerComesAndGoes() {
        connections[0].serverSends(
            """{"cmd":"onlineAdd","nick":"peer","userid":7,"channel":"testroom"}"""
        )
        connections[0].serverSends(
            """{"cmd":"onlineRemove","nick":"peer","userid":7,"channel":"testroom"}"""
        )
    }

    @Test
    fun onByDefault() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = SessionManager(
            scope = this,
            initialUrl = TEST_URL,
            transport = transport,
            now = { 0L },
            customIdFactory = { "abc123" },
        )
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        transport.peerComesAndGoes()
        advanceUntilIdle()

        val kinds = sessions.channels.value.getValue("testroom").messages.map { it.kind }
        assertTrue(MessageKind.Join in kinds, "a join went unrecorded with the default setting")
        assertTrue(MessageKind.Leave in kinds, "a leave went unrecorded with the default setting")
        sessions.stopAll()
    }

    @Test
    fun suppressedWhenOff() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this) { false }
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        transport.peerComesAndGoes()
        advanceUntilIdle()

        val kinds = sessions.channels.value.getValue("testroom").messages.map { it.kind }
        assertTrue(MessageKind.Join !in kinds, "a join was buffered with the setting off")
        assertTrue(MessageKind.Leave !in kinds, "a leave was buffered with the setting off")
        sessions.stopAll()
    }

    /** Who is here is a different question from who just arrived. */
    @Test
    fun theRosterStillTracksThem() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this) { false }
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        transport.connections[0].serverSends(
            """{"cmd":"onlineAdd","nick":"peer","userid":7,"channel":"testroom"}"""
        )
        advanceUntilIdle()

        assertEquals(
            listOf("alice", "peer"),
            sessions.channels.value.getValue("testroom").roster.map { it.nick },
        )
        sessions.stopAll()
    }

    /**
     * Read per event, not captured once: the setting is changed from the
     * settings sheet while the service — and this manager — stay alive.
     */
    @Test
    fun theSettingIsRereadPerEvent() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        var show = false
        val sessions = manager(transport, this) { show }
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        transport.connections[0].serverSends(
            """{"cmd":"onlineAdd","nick":"early","userid":7,"channel":"testroom"}"""
        )
        advanceUntilIdle()

        show = true
        transport.connections[0].serverSends(
            """{"cmd":"onlineAdd","nick":"late","userid":8,"channel":"testroom"}"""
        )
        advanceUntilIdle()

        val joins = sessions.channels.value.getValue("testroom").messages
            .filter { it.kind == MessageKind.Join }
            .map { it.nick }
        assertEquals(listOf("late"), joins)
        sessions.stopAll()
    }
}
