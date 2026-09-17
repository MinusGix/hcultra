package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
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
 * Being invited somewhere.
 *
 * A v2 socket is sent `cmd:'invite'` and nothing else — no prose, and none of
 * the `info` framing a v1 client gets — so an invite reaches the user only if
 * this layer composes the line itself. It used to be dropped on the floor,
 * which made an invite completely invisible: no transcript line, no unread, no
 * notification.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerInviteTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    /** Us as `alice`/99, with `bob`/7 in the room to invite and be invited by. */
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
            initialUrl = TEST_URL,
            transport = transport,
            now = { 0L },
            customIdFactory = { "abc123" },
        )

    @Test
    fun anIncomingInviteLandsInTheTranscript() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        val info = sessions.channels.value.getValue("testroom").messages
            .filter { it.kind == MessageKind.Info }
            .map { it.text }
        assertTrue(
            "bob invited you to ?p7rl8pem" in info,
            "the invite never reached the transcript; info lines were $info",
        )
        sessions.stopAll()
    }

    /**
     * The inviter is a userid on the wire. Resolved against the roster on
     * receipt, because they may leave before the transcript is read.
     */
    @Test
    fun anInviterWhoIsNotInTheRosterStillReads() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":404,"to":99,"inviteChannel":"x"}"""
        )
        advanceUntilIdle()

        val info = sessions.channels.value.getValue("testroom").messages.map { it.text }
        assertTrue(
            "user 404 invited you to ?x" in info,
            "an unknown inviter should still produce a line; got $info",
        )
        sessions.stopAll()
    }

    /** `?channel` is what the renderer linkifies, so the invite is actionable. */
    @Test
    fun theOfferedChannelIsWrittenAsAChannelReference() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"somewhere"}"""
        )
        advanceUntilIdle()

        val line = sessions.channels.value.getValue("testroom").messages
            .first { it.text.contains("invited") }
        assertTrue("?somewhere" in line.text, "no channel reference to tap in: ${line.text}")
        sessions.stopAll()
    }

    @Test
    fun anIncomingInviteAlerts() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        assertEquals(1, seen.size, "expected exactly one alert, got $seen")
        assertEquals(Alert.Kind.Invite, seen[0].kind)
        assertEquals("bob", seen[0].nick)
        // The nick is the alert's sender, so the text must not repeat it.
        assertEquals("invited you to ?p7rl8pem", seen[0].text)
        collector.cancel()
        sessions.stopAll()
    }

    /** A channel not on screen has to say something happened in it. */
    @Test
    fun anIncomingInviteMarksTheChannelUnread() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.activeChannel = "elsewhere"
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        assertEquals(1, sessions.channels.value.getValue("testroom").unread)
        sessions.stopAll()
    }

    // --- sending ----------------------------------------------------------

    private fun Iterable<String>.invites() =
        map { Json.parseToJsonElement(it).jsonObject }.filter {
            it["cmd"]?.jsonPrimitive?.content == "invite"
        }

    /**
     * A userid and an explicit channel, which is what a v2 socket must send. A
     * nick-only payload is *silently dropped* — no reply, and the rate-limit
     * points spent anyway — so getting this wrong looks exactly like success.
     */
    @Test
    fun sendingAnInviteNamesTheTargetByUserid() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        val bob = sessions.channels.value.getValue("testroom").roster.first { it.nick == "bob" }
        assertTrue(sessions.invite("testroom", bob), "the invite was not sent")
        advanceUntilIdle()

        val sent = transport.connections[0].sent.invites()
        assertEquals(1, sent.size, "expected exactly one invite frame, got $sent")
        assertEquals(7L, sent[0]["userid"]?.jsonPrimitive?.content?.toLong())
        assertEquals("testroom", sent[0]["channel"]?.jsonPrimitive?.content)
        sessions.stopAll()
    }

    /**
     * No `to`, so `getChannel` invents one. Sending it — even as null — would
     * be us choosing the destination, and the whole point is that the server
     * names somewhere new and tells both of us through the invite it echoes.
     */
    @Test
    fun sendingAnInviteLetsTheServerNameTheChannel() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        val bob = sessions.channels.value.getValue("testroom").roster.first { it.nick == "bob" }
        sessions.invite("testroom", bob)
        advanceUntilIdle()

        val frame = transport.connections[0].sent.invites().single()
        assertTrue("to" !in frame, "we named the destination ourselves: $frame")
        sessions.stopAll()
    }

    /** Legal server-side, useless, and it still costs 2 of 25 rate points. */
    @Test
    fun invitingYourselfIsNotSent() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        val me = sessions.channels.value.getValue("testroom").roster.first { it.isme }
        assertFalse(sessions.invite("testroom", me), "a self-invite reported success")
        advanceUntilIdle()

        assertTrue(
            transport.connections[0].sent.invites().isEmpty(),
            "a self-invite reached the wire",
        )
        sessions.stopAll()
    }

    @Test
    fun invitingIntoAChannelWeAreNotInIsNotSent() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        val bob = sessions.channels.value.getValue("testroom").roster.first { it.nick == "bob" }
        assertFalse(sessions.invite("elsewhere", bob), "an invite into a channel we have not joined")
        sessions.stopAll()
    }

    /**
     * `invite.js` replies to the inviter with the *same* frame it sends the
     * target, so our own invite comes back to us. It reads as ours and must not
     * buzz us about something we just did.
     */
    @Test
    fun ourOwnInviteEchoesAsOursAndDoesNotAlert() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.activeChannel = "elsewhere"
        sessions.join("testroom", Credentials("alice"))
        advanceUntilIdle()

        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":99,"to":7,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        val info = sessions.channels.value.getValue("testroom").messages.map { it.text }
        assertTrue(
            "You invited bob to ?p7rl8pem" in info,
            "our own invite should be recorded from our side; got $info",
        )
        assertTrue(seen.isEmpty(), "our own invite alerted us: $seen")
        assertEquals(
            0,
            sessions.channels.value.getValue("testroom").unread,
            "our own invite marked the channel unread",
        )
        collector.cancel()
        sessions.stopAll()
    }
}
