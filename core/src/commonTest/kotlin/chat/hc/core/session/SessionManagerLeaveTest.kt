package chat.hc.core.session

import chat.hc.core.FakeTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Closing a channel, and taking it back.
 *
 * The leave is deferred rather than undone because the two differ where it
 * matters: the server keeps no history, so a leave-then-rejoin cannot restore
 * the transcript, and it costs the whole channel a visible leave/join pair.
 * These tests pin the property that makes the undo worth having — that taking
 * it back sends nothing at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerLeaveTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

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
            closeGraceMillis = GRACE,
        )

    private suspend fun joined(transport: FakeTransport, scope: kotlinx.coroutines.CoroutineScope) =
        manager(transport, scope).also {
            it.join("testroom", Credentials("alice"))
        }

    @Test
    fun closingHidesTheChannelButKeepsItConnected() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()

        sessions.beginLeave("testroom")
        // runCurrent, not advanceUntilIdle: the latter would run virtual time
        // out to the end of the grace period and commit the very leave this is
        // checking is still pending.
        runCurrent()

        val ui = sessions.channels.value["testroom"]
        assertTrue(ui != null && ui.closing, "close did not mark the channel closing")
        assertTrue(transport.connections[0].isOpen, "closed the socket before the grace period")
        sessions.stopAll()
    }

    /** The whole point: an undo is invisible to the channel. */
    @Test
    fun undoSendsNothingAndKeepsTheScrollback() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()
        transport.connections[0].serverSends(
            """{"cmd":"chat","nick":"peer","userid":7,"text":"said before the close","channel":"testroom"}"""
        )
        advanceUntilIdle()
        val sentBefore = transport.connections[0].sent.size

        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE / 2)
        sessions.undoLeave("testroom")
        advanceUntilIdle()

        assertEquals(
            sentBefore,
            transport.connections[0].sent.size,
            "an undo put traffic on the wire; the channel would have seen us leave",
        )
        assertEquals(1, transport.connections.size, "undo reconnected instead of holding the socket")
        val ui = sessions.channels.value.getValue("testroom")
        assertFalse(ui.closing, "still marked closing after the undo")
        assertTrue(
            ui.messages.any { it.text == "said before the close" },
            "the scrollback was dropped: ${ui.messages.map { it.text }}",
        )
        sessions.stopAll()
    }

    /** Left alone, the close still happens. */
    @Test
    fun theGracePeriodCommitsTheLeave() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()

        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE + 1)
        advanceUntilIdle()

        assertNull(sessions.channels.value["testroom"], "the channel survived its grace period")
        assertFalse(transport.connections[0].isOpen, "the socket was left open after leaving")
    }

    /** An undo landing after the grace period must not resurrect a dead channel. */
    @Test
    fun undoAfterTheGracePeriodDoesNothing() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()

        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE + 1)
        advanceUntilIdle()
        sessions.undoLeave("testroom")
        advanceUntilIdle()

        assertNull(sessions.channels.value["testroom"], "undo brought back a channel already left")
    }

    /** Closing twice must not restart the clock, or a jabbed × never lands. */
    @Test
    fun closingTwiceDoesNotExtendTheGracePeriod() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()

        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE / 2)
        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE / 2 + 1)
        advanceUntilIdle()

        assertNull(sessions.channels.value["testroom"], "the second close pushed the leave back")
    }

    /**
     * Re-joining a channel on its way out is a take-back too. Without this the
     * join hits the same-identity no-op and the channel stays hidden, waiting to
     * disappear.
     */
    @Test
    fun rejoiningAClosingChannelCallsOffTheLeave() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = joined(transport, this)
        advanceUntilIdle()

        sessions.beginLeave("testroom")
        advanceTimeBy(GRACE / 2)
        sessions.join("testroom", Credentials("alice"))
        advanceTimeBy(GRACE)
        advanceUntilIdle()

        val ui = sessions.channels.value["testroom"]
        assertTrue(ui != null && !ui.closing, "re-joining did not call off the leave")
        assertEquals(1, transport.connections.size, "re-joining opened a second socket")
        sessions.stopAll()
    }

    private companion object {
        const val GRACE = 8_000L
    }
}
