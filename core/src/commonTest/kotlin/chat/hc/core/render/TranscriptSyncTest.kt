package chat.hc.core.render

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the renderer is told, and — mostly — what it is spared.
 *
 * These assert on the JS statements rather than on internal state, because the
 * statements are the contract: the page draws exactly what arrives and keeps no
 * opinion of its own about what changed.
 */
class TranscriptSyncTest {

    private fun msg(id: Long, text: String, delivery: Delivery = Delivery.Sent) =
        ChatMessage(localId = id, kind = MessageKind.Chat, nick = "bob", text = text, delivery = delivery)

    private fun transcript(n: Int, prefix: String = "m") =
        (1..n).map { msg(it.toLong(), "$prefix$it") }

    private fun List<String>.applies() = filter { it.startsWith("HC.apply(") }
    private fun List<String>.shows() = filter { it.startsWith("HC.show(") }
    private fun List<String>.evicts() = filter { it.startsWith("HC.evict(") }

    /** Rough stand-in for payload weight: how much of the wire is message bodies. */
    private fun String.upsertCount(): Int = Regex("\\\\\"localId\\\\\":").findAll(this).count()

    @Test
    fun aChannelNeverShownBeforeIsSentInFull() {
        val sync = TranscriptSync()
        val calls = sync.update("alpha", transcript(3))

        assertEquals(1, calls.applies().size)
        assertTrue(calls.applies()[0].contains("full\\\":true"), calls.applies()[0])
        assertEquals(3, calls.applies()[0].upsertCount())
        assertEquals(1, calls.shows().size)
    }

    @Test
    fun anUnchangedRedrawSaysNothingAtAll() {
        val sync = TranscriptSync()
        val messages = transcript(3)
        sync.update("alpha", messages)

        assertEquals(emptyList(), sync.update("alpha", messages))
    }

    /**
     * The long-session case. One arriving message must cost one row, not the
     * whole transcript: it was the full snapshot every time that made the cost
     * of receiving a message grow with how long you had been in the room.
     */
    @Test
    fun anArrivingMessageSendsOneRow() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(200))

        val calls = sync.update("alpha", transcript(200) + msg(201, "new"))
        val apply = calls.applies().single()

        assertTrue(apply.contains("full\\\":false"), apply)
        assertEquals(1, apply.upsertCount(), "only the arriving row should carry a body")
        assertEquals(emptyList(), calls.shows(), "an arriving message is not a channel switch")
    }

    /** An edit — `updateMessage`, or a delivery state settling — is the same shape. */
    @Test
    fun anEditedRowIsTheOnlyOneResent() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(50))

        val edited = transcript(50).toMutableList()
        edited[20] = msg(21, "edited")
        val apply = sync.update("alpha", edited).applies().single()

        assertEquals(1, apply.upsertCount())
        assertTrue(apply.contains("edited"), apply)
    }

    @Test
    fun aDeliveryChangeCountsAsAChange() {
        val sync = TranscriptSync()
        sync.update("alpha", listOf(msg(1, "hi", Delivery.Sending)))

        val apply = sync.update("alpha", listOf(msg(1, "hi", Delivery.Sent))).applies().single()
        assertEquals(1, apply.upsertCount())
    }

    /** Trimming at the buffer cap drops ids from the front and adds none. */
    @Test
    fun aTrimmedFrontIsCarriedByTheOrderAlone() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(5))

        val apply = sync.update("alpha", transcript(5).drop(1)).applies().single()
        assertEquals(0, apply.upsertCount(), "dropping rows should not resend any body")
        assertTrue(!apply.contains("[1,"), "id 1 should be gone from the order: $apply")
    }

    /**
     * The whole point of the change. Coming back to a channel the page still
     * holds must cost a `show` and nothing else — no payload, no re-render.
     */
    @Test
    fun returningToAHeldChannelOnlyShowsIt() {
        val sync = TranscriptSync()
        val alpha = transcript(100, "a")
        val beta = transcript(100, "b")
        sync.update("alpha", alpha)
        sync.update("beta", beta)

        val back = sync.update("alpha", alpha)

        assertEquals(emptyList(), back.applies(), "a swap re-rendered rows the page already had")
        assertEquals(1, back.shows().size)
    }

    @Test
    fun switchingToANewChannelShowsItAfterFillingIt() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(2))
        val calls = sync.update("beta", transcript(2, "b"))

        // Content before visibility, or the reader sees an empty container.
        assertEquals(2, calls.size)
        assertTrue(calls[0].startsWith("HC.apply("), calls[0])
        assertTrue(calls[1].startsWith("HC.show("), calls[1])
    }

    // --- retention ---------------------------------------------------------

    @Test
    fun onlyTheLastFewChannelsKeepTheirDom() {
        val sync = TranscriptSync(residentLimit = 2)
        sync.update("a", transcript(1))
        sync.update("b", transcript(1))
        val calls = sync.update("c", transcript(1))

        assertEquals(listOf("HC.evict(\"a\");"), calls.evicts())
    }

    @Test
    fun anEvictedChannelIsRebuiltInFullOnReturn() {
        val sync = TranscriptSync(residentLimit = 2)
        sync.update("a", transcript(3))
        sync.update("b", transcript(3))
        sync.update("c", transcript(3))

        val apply = sync.update("a", transcript(3)).applies().single()
        assertTrue(apply.contains("full\\\":true"), "an evicted channel must not be patched: $apply")
        assertEquals(3, apply.upsertCount())
    }

    /** Whatever the limit says, the page must never be told to drop what is on screen. */
    @Test
    fun theVisibleChannelIsNeverEvicted() {
        val sync = TranscriptSync(residentLimit = 1)
        sync.update("a", transcript(1))
        val calls = sync.update("b", transcript(1))

        assertTrue(calls.evicts().none { it.contains("\"b\"") }, calls.evicts().toString())
        assertEquals(listOf("HC.evict(\"a\");"), calls.evicts())
    }

    /** Recency is by last shown, not by first seen. */
    @Test
    fun evictionTakesTheLeastRecentlyShown() {
        val sync = TranscriptSync(residentLimit = 2)
        sync.update("a", transcript(1))
        sync.update("b", transcript(1))
        sync.update("a", transcript(1))   // a is now the more recent of the two

        assertEquals(listOf("HC.evict(\"b\");"), sync.update("c", transcript(1)).evicts())
    }

    // --- the page going away ----------------------------------------------

    /**
     * A reload leaves the page empty whatever we believed. Patching then would
     * describe rows that do not exist, and the transcript would sit half-drawn
     * with nothing able to notice.
     */
    @Test
    fun aResetMakesTheNextUpdateFullAgain() {
        val sync = TranscriptSync()
        val messages = transcript(4)
        sync.update("alpha", messages)
        sync.reset()

        val calls = sync.update("alpha", messages)
        assertTrue(calls.applies().single().contains("full\\\":true"))
        assertEquals(1, calls.shows().size, "the page has no visible channel after a reset")
    }

    // --- everything on the wire is data ------------------------------------

    /**
     * A channel name is server-supplied — `?"};alert(1)//` is a channel anyone
     * can make — so it reaches the page as a JSON string literal rather than
     * interpolated into the statement. Asserted by round-trip, because "looks
     * escaped" is exactly the check that passes while being wrong.
     */
    @Test
    fun aHostileChannelNameStaysData() {
        val hostile = "\");alert(1);//"
        val sync = TranscriptSync()
        val show = sync.update(hostile, transcript(1)).shows().single()

        val argument = show.removePrefix("HC.show(").removeSuffix(");")
        assertEquals(
            hostile,
            Json.decodeFromString(String.serializer(), argument),
            "the channel name closed the call instead of staying inside it",
        )
    }

    /** And so is message text, which is the more obvious hostile input. */
    @Test
    fun hostileMessageTextStaysInsideThePayload() {
        val nasty = "</script><img onerror=alert(1)>\"};"
        val sync = TranscriptSync()
        val apply = sync.update("alpha", listOf(msg(1, nasty))).applies().single()

        // Two levels, because that is what the page sees: a JSON document
        // carried inside a JSON string literal. Decoding only the outer one
        // still shows escaped quotes and would pass for the wrong reason.
        val argument = apply.substringAfter(", ").removeSuffix(");")
        val payload = Json.decodeFromString(String.serializer(), argument)
        val text = Json.parseToJsonElement(payload)
            .jsonObject.getValue("upsert")
            .jsonArray[0]
            .jsonObject.getValue("text")
            .jsonPrimitive.content

        assertEquals(nasty, text, "message text did not survive as data")
    }
}
