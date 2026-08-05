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
 * Joining a channel you are already in.
 *
 * hack.chat has no rename, so changing nick means a new socket — and the
 * manager is the only thing that knows a channel is already open, so it is the
 * only thing that can tell "join again" from "become someone else".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerJoinTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    private fun nickOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["nick"]?.jsonPrimitive?.content

    private fun FakeTransport.scriptJoin() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(
                    """{"cmd":"onlineSet","users":[{"isme":true,"nick":${
                        Json.parseToJsonElement(raw).jsonObject["nick"]
                    },"userid":99}],"channel":"testroom"}"""
                )
            }
        }
    }

    private fun manager(transport: FakeTransport, scope: kotlinx.coroutines.CoroutineScope) =
        SessionManager(
            scope = scope,
            initialUrl = TEST_URL,
            transport = transport,
            now = { 0L },
            customIdFactory = { "abc123" },
        )

    /** The server rejects a second join on one socket, so this must do nothing. */
    @Test
    fun rejoiningAsTheSamePersonIsANoOp() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        assertEquals(1, transport.connections.size, "opened a second socket for the same identity")
        sessions.stopAll()
    }

    /**
     * The bug as it appeared on-device: with the channel already on screen, the
     * join form's nick was simply discarded.
     */
    @Test
    fun joiningAsSomeoneElseReplacesTheSession() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        sessions.join("testroom", Credentials("bob"))
        advanceUntilIdle()

        assertEquals(2, transport.connections.size, "did not open a socket for the new identity")
        assertEquals("bob", nickOf(transport.connections[1].sent[1]))
        assertEquals(
            listOf("alice"),
            transport.connections[0].sent.filter { cmdOf(it) == "join" }.map(::nickOf),
        )
        sessions.stopAll()
    }

    /** hack.chat keeps no history, so a change of nick must not discard ours. */
    @Test
    fun changingIdentityKeepsTheScrollback() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()
        transport.connections[0].serverSends(
            """{"cmd":"chat","nick":"peer","userid":7,"text":"said before the switch","channel":"testroom"}"""
        )
        advanceUntilIdle()

        sessions.join("testroom", Credentials("bob"))
        advanceUntilIdle()

        val messages = sessions.channels.value.getValue("testroom").messages
        assertTrue(
            messages.any { it.text == "said before the switch" },
            "the scrollback was dropped on re-identify",
        )
        assertTrue(
            messages.any { it.kind == MessageKind.Info && it.text == "Rejoining as bob." },
            "no note that the identity changed: ${messages.map { it.text }}",
        )
        sessions.stopAll()
    }
}
