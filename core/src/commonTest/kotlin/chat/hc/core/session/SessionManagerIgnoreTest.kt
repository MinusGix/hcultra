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
import kotlin.test.assertTrue

/**
 * Ignoring someone.
 *
 * Their messages are still buffered — hiding them is the renderer's job, so
 * that stopping ignoring them shows what was missed — but none of it may mark
 * a tab unread or raise an alert. It did both, since the list once lived only
 * in the UI and the service, which notifies, never heard of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerIgnoreTest {

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

    /** A mention, an emote mention and a whisper: each alone would alert. */
    private suspend fun FakeTransport.bobAddressesUs() {
        connections[0].serverSends(
            """{"cmd":"chat","nick":"bob","userid":7,"channel":"testroom","text":"hey @alice"}"""
        )
        connections[0].serverSends(
            """{"cmd":"emote","nick":"bob","userid":7,"channel":"testroom","text":"@bob pokes @alice"}"""
        )
        connections[0].serverSends(
            """{"cmd":"whisper","channel":"testroom","from":7,"to":99,"text":"psst"}"""
        )
    }

    @Test
    fun anIgnoredNickNeitherAlertsNorCountsAsUnread() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.setIgnored("bob", true)
        transport.bobAddressesUs()
        advanceUntilIdle()

        val ui = sessions.channels.value.getValue(key("testroom"))
        assertEquals(emptyList(), seen, "an ignored nick alerted")
        assertEquals(0, ui.unread, "an ignored nick marked the tab unread")
        assertEquals(
            listOf(MessageKind.Chat, MessageKind.Emote, MessageKind.Whisper),
            ui.messages.filter { it.nick == "bob" }.map { it.kind },
            "an ignored nick's messages must still be buffered, to show again if unignored",
        )
        collector.cancel()
        sessions.stopAll()
    }

    @Test
    fun stoppingIgnoringRestoresAlerts() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.setIgnored("bob", true)
        sessions.setIgnored("bob", false)
        transport.bobAddressesUs()
        advanceUntilIdle()

        assertEquals(
            listOf(Alert.Kind.Mention, Alert.Kind.Mention, Alert.Kind.Whisper),
            seen.map { it.kind },
        )
        assertEquals(3, sessions.channels.value.getValue(key("testroom")).unread)
        collector.cancel()
        sessions.stopAll()
    }

    /** Ignoring one person is not ignoring the room. */
    @Test
    fun othersStillAlert() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.setIgnored("carol", true)
        transport.connections[0].serverSends(
            """{"cmd":"chat","nick":"bob","userid":7,"channel":"testroom","text":"hey @alice"}"""
        )
        advanceUntilIdle()

        assertTrue(seen.any { it.nick == "bob" }, "ignoring carol silenced bob")
        collector.cancel()
        sessions.stopAll()
    }

    /**
     * An ignored inviter's line is kept — it is an info line with no nick of
     * its own, so nothing could hide it and show it again — but it neither
     * alerts nor counts.
     */
    @Test
    fun anIgnoredInviteIsKeptButQuiet() = runTest {
        val transport = FakeTransport().apply { scriptJoin() }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.setIgnored("bob", true)
        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        val ui = sessions.channels.value.getValue(key("testroom"))
        assertEquals(emptyList(), seen)
        assertEquals(0, ui.unread)
        assertTrue(ui.messages.any { it.kind == MessageKind.Info && "bob invited you" in it.text }, "${ui.messages}")
        collector.cancel()
        sessions.stopAll()
    }

    /** One nick at a time, in and out, leaving the rest of the list alone. */
    @Test
    fun ignoringAndUnignoringEditTheList() = runTest {
        val sessions = manager(FakeTransport(), this)
        sessions.setIgnored("bob", true)
        sessions.setIgnored("carol", true)
        sessions.setIgnored("bob", false)
        assertEquals(setOf("carol"), sessions.ignored.value.nicks)
        sessions.stopAll()
    }

    /** Trips the same way, and apart from the nicks. A blank trip is no trip. */
    @Test
    fun ignoringAndUnignoringTripsEditTheList() = runTest {
        val sessions = manager(FakeTransport(), this)
        sessions.setIgnored("bob", true)
        sessions.setTripIgnored("Bb0bTr", true)
        sessions.setTripIgnored("Cc4rol", true)
        sessions.setTripIgnored(" ", true)
        sessions.setTripIgnored("Cc4rol", false)
        assertEquals(IgnoreList(nicks = setOf("bob"), trips = setOf("Bb0bTr")), sessions.ignored.value)
        sessions.stopAll()
    }

    /**
     * An ignored trip follows whoever holds it, under any nick: here bob,
     * renamed, says the same things and none of them alert or count.
     */
    @Test
    fun anIgnoredTripIsQuietUnderAnyNick() = runTest {
        val transport = FakeTransport().apply {
            onSend = { raw ->
                when (cmdOf(raw)) {
                    "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                    "join" -> serverSends(
                        """{"cmd":"onlineSet","users":[""" +
                            """{"isme":true,"nick":"alice","userid":99},""" +
                            """{"isme":false,"nick":"bobby","userid":7,"trip":"Bb0bTr"}""" +
                            """],"channel":"testroom"}"""
                    )
                }
            }
        }
        val sessions = manager(transport, this)
        val seen = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { seen += it } }
        sessions.activeChannel = key("elsewhere")
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        sessions.setTripIgnored("Bb0bTr", true)
        transport.connections[0].serverSends(
            """{"cmd":"chat","nick":"bobby","userid":7,"trip":"Bb0bTr","channel":"testroom","text":"hey @alice"}"""
        )
        transport.connections[0].serverSends(
            """{"cmd":"emote","nick":"bobby","userid":7,"trip":"Bb0bTr","channel":"testroom","text":"@bobby pokes @alice"}"""
        )
        transport.connections[0].serverSends(
            """{"cmd":"whisper","channel":"testroom","from":7,"to":99,"trip":"Bb0bTr","text":"psst"}"""
        )
        // An invite frame carries no trip; it is read off the roster.
        transport.connections[0].serverSends(
            """{"cmd":"invite","channel":"testroom","from":7,"to":99,"inviteChannel":"p7rl8pem"}"""
        )
        advanceUntilIdle()

        val ui = sessions.channels.value.getValue(key("testroom"))
        assertEquals(emptyList(), seen, "an ignored trip alerted")
        assertEquals(0, ui.unread, "an ignored trip marked the tab unread")
        assertEquals(
            listOf("Bb0bTr", "Bb0bTr", "Bb0bTr"),
            ui.messages.filter { it.nick == "bobby" }.map { it.trip },
            "the trip must be kept on what they said, emotes included, for the transcript to hide it by",
        )
        collector.cancel()
        sessions.stopAll()
    }

    @Test
    fun hidesWhatTheySayNotThatTheyCameOrWent() {
        val ignored = IgnoreList(nicks = setOf("bob"))
        fun msg(kind: MessageKind, nick: String = "bob", mine: Boolean = false) =
            chat.hc.core.store.ChatMessage(0, kind, nick, isMine = mine)

        assertTrue(Ignore.hides(msg(MessageKind.Chat), ignored))
        assertTrue(Ignore.hides(msg(MessageKind.Emote), ignored))
        assertTrue(Ignore.hides(msg(MessageKind.Whisper), ignored))
        // The join or leave line is where their nick can be held to undo it.
        assertTrue(!Ignore.hides(msg(MessageKind.Join), ignored))
        assertTrue(!Ignore.hides(msg(MessageKind.Leave), ignored))
        // A whisper we sent them names them, but it is ours.
        assertTrue(!Ignore.hides(msg(MessageKind.WhisperSent, mine = true), ignored))
        assertTrue(!Ignore.hides(msg(MessageKind.Chat, nick = "carol"), ignored))
        // Our own message is never hidden from us, even under an ignored nick.
        assertTrue(!Ignore.hides(msg(MessageKind.Chat, mine = true), ignored))
        assertTrue(!Ignore.hides(msg(MessageKind.Chat), IgnoreList()))
    }

    @Test
    fun aTripHidesWhoeverHoldsIt() {
        val ignored = IgnoreList(trips = setOf("Bb0bTr"))
        fun msg(nick: String, trip: String?, kind: MessageKind = MessageKind.Chat, mine: Boolean = false) =
            chat.hc.core.store.ChatMessage(0, kind, nick, trip = trip, isMine = mine)

        assertTrue(Ignore.hides(msg("bob", "Bb0bTr"), ignored))
        assertTrue(Ignore.hides(msg("anyone", "Bb0bTr", MessageKind.Emote), ignored))
        assertTrue(Ignore.hides(msg("anyone", "Bb0bTr", MessageKind.Whisper), ignored))
        // Same nick, another trip or none: not them.
        assertTrue(!Ignore.hides(msg("bob", "Xx0ther"), ignored))
        assertTrue(!Ignore.hides(msg("bob", null), ignored))
        assertTrue(!Ignore.hides(msg("anyone", "Bb0bTr", MessageKind.Join), ignored))
        assertTrue(!Ignore.hides(msg("me", "Bb0bTr", mine = true), ignored))
        // A blank trip never matches, even if one got into the list.
        assertTrue(!Ignore.hides(msg("anyone", ""), IgnoreList(trips = setOf(""))))
    }
}
