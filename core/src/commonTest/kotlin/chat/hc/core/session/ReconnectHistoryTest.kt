package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import chat.hc.core.store.TranscriptCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a channel shows when it comes back: everything it showed before, and a
 * line saying how long it was gone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectHistoryTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    /** A cold join into a room where a peer says something. */
    private fun FakeTransport.scriptColdJoin() {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"alice","userid":99}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"session","restored":false,"token":"tok","channels":["testroom"]}""")
                    serverSends("""{"cmd":"chat","nick":"bob","userid":7,"text":"before the drop","channel":"testroom"}""")
                }
            }
        }
    }

    private fun FakeTransport.scriptRestore() {
        onSend = { raw ->
            if (cmdOf(raw) == "session") {
                serverSends("""{"cmd":"session","restored":true,"token":"tok","channels":["testroom"]}""")
                serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"alice","userid":99}],"channel":"testroom"}""")
            }
        }
    }

    private fun manager(
        transport: FakeTransport,
        scope: CoroutineScope,
        tokens: TokenStore = InMemoryTokenStore(),
        transcripts: TranscriptStore = InMemoryTranscriptStore(),
        now: () -> Long,
    ) = SessionManager(
        scope = scope,
        transport = transport,
        tokenStore = tokens,
        transcriptStore = transcripts,
        now = now,
        customIdFactory = { "abc123" },
    )

    private fun texts(sessions: SessionManager) =
        sessions.channels.value[key("testroom")]!!.messages.map { it.text }

    @Test
    fun durationsReadAsTwoUnitsAtMost() {
        assertEquals("less than a second", Downtime.describe(400))
        assertEquals("1 second", Downtime.describe(1_000))
        assertEquals("42 seconds", Downtime.describe(42_000))
        assertEquals("1 minute", Downtime.describe(60_000))
        assertEquals("3 minutes, 12 seconds", Downtime.describe(192_000))
        assertEquals("2 hours, 5 minutes", Downtime.describe((2 * 3600 + 5 * 60 + 9) * 1000L))
        // A zero in the middle is skipped, not counted as one of the two.
        assertEquals("1 hour", Downtime.describe((3600 + 30) * 1000L))
        assertEquals("3 days, 1 hour", Downtime.describe((3 * 86400 + 3600 + 61) * 1000L))
    }

    /** The case that already kept its history; now it also says how long. */
    @Test
    fun aDroppedSocketKeepsItsHistoryAndSaysHowLong() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, now = { testScheduler.currentTime })
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        transport.scriptRestore()
        transport.latest.kill()
        advanceUntilIdle()

        val lines = texts(sessions)
        assertTrue("before the drop" in lines, "history was lost: $lines")
        val notice = lines.indexOf("Reconnected. You were disconnected for 1 second.")
        assertTrue(notice > lines.indexOf("before the drop"), "no reconnect notice below the history: $lines")
        assertTrue(lines.none { it == "Reconnected." }, "both notices: $lines")
        sessions.stopAll()
    }

    /**
     * The reported bug: the notification's Disconnect, or Android reclaiming
     * the service, took the whole conversation with it.
     */
    @Test
    fun aNewProcessGetsTheHistoryBackAndTheTimeAway() = runTest {
        var clock = 1_000_000L
        val tokens = InMemoryTokenStore()
        val transcripts = InMemoryTranscriptStore()

        val first = FakeTransport().apply { scriptColdJoin() }
        val one = manager(first, this, tokens, transcripts) { clock }
        one.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        one.stopAll()
        advanceUntilIdle()

        clock += (2 * 3600 + 5 * 60) * 1000L
        val second = FakeTransport().apply { scriptRestore() }
        val two = manager(second, this, tokens, transcripts) { clock }
        two.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        val lines = texts(two)
        assertEquals("before the drop", lines.first { it == "before the drop" })
        assertTrue(
            lines.indexOf("Reconnected. You were disconnected for 2 hours, 5 minutes.") > lines.indexOf("before the drop"),
            "no notice below the restored history: $lines",
        )
        two.stopAll()
    }

    /** Closing the tab is the user being done with it, so nothing is kept. */
    @Test
    fun leavingForgetsTheSavedHistory() = runTest {
        val transcripts = InMemoryTranscriptStore()
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, transcripts = transcripts, now = { 0L })
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        sessions.saveTranscripts()
        assertTrue(transcripts.saved.containsKey(key("testroom")))

        sessions.leave(key("testroom"))
        assertNull(transcripts.saved[key("testroom")])
    }

    /** A channel closed but still in its grace period is not kept by a stop either. */
    @Test
    fun stoppingDuringACloseForgetsThatChannel() = runTest {
        val transcripts = InMemoryTranscriptStore()
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, transcripts = transcripts, now = { 0L })
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        sessions.saveTranscripts()

        sessions.beginLeave(key("testroom"))
        sessions.stopAll()
        assertNull(transcripts.saved[key("testroom")])
    }

    /** Only a changed channel is rewritten; a quiet one only says it is still here. */
    @Test
    fun aQuietChannelIsTouchedNotRewritten() = runTest {
        var clock = 5_000L
        val transcripts = InMemoryTranscriptStore()
        val transport = FakeTransport().apply { scriptColdJoin() }
        val sessions = manager(transport, this, transcripts = transcripts) { clock }
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        sessions.saveTranscripts()
        val written = transcripts.saved[key("testroom")]!!.messages

        clock = 65_000L
        sessions.saveTranscripts()
        val saved = transcripts.saved[key("testroom")]!!
        assertTrue(saved.messages === written, "an unchanged transcript was written again")
        assertEquals(65_000L, saved.lastSeen)
        sessions.stopAll()
    }

    /** A message that never got its echo before the process died never will. */
    @Test
    fun aMessageStillSendingComesBackUnconfirmed() = runTest {
        val transcripts = InMemoryTranscriptStore()
        transcripts.save(
            key("testroom"),
            listOf(ChatMessage(0, MessageKind.Chat, "alice", text = "hello?", isMine = true, delivery = Delivery.Sending)),
            lastSeen = 0L,
        )
        val transport = FakeTransport().apply { scriptRestore() }
        val sessions = manager(transport, this, transcripts = transcripts, now = { 0L })
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        val mine = sessions.channels.value[key("testroom")]!!.messages.first { it.text == "hello?" }
        assertEquals(Delivery.Unconfirmed, mine.delivery)
        sessions.stopAll()
    }

    @Test
    fun theCodecRoundTripsAndShrugsOffGarbage() {
        val messages = listOf(
            ChatMessage(4, MessageKind.Chat, "bob", 7, trip = "abc", text = "hi", flair = "⭐", level = 100, at = 12, customId = "x1", isMine = false),
            ChatMessage(5, MessageKind.WhisperSent, "carol", 8, text = "psst", at = 13, isMine = true, delivery = Delivery.Unconfirmed),
            ChatMessage(6, MessageKind.Info, text = "Users online: bob", at = 14, veiled = true),
        )
        val back = TranscriptCodec.decode(TranscriptCodec.encode(messages))
        // The local id is the buffer's to hand out, and veiling is the view's.
        assertEquals(messages.map { it.copy(localId = 0, veiled = false) }, back)

        assertEquals(emptyList(), TranscriptCodec.decode("not json"))
        assertEquals(emptyList(), TranscriptCodec.decode("""{"messages":[{"kind":"Nonsense"}]}"""))
    }
}
