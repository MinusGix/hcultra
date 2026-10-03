package chat.hc.core.render

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    /** The patch an `HC.apply` statement carries, decoded the way the page decodes it. */
    private fun String.patch(): JsonObject {
        val argument = substringAfter(", ").removeSuffix(");")
        return Json.parseToJsonElement(Json.decodeFromString(String.serializer(), argument)).jsonObject
    }

    private fun String.full() = patch().getValue("full").jsonPrimitive.boolean
    private fun String.atStart() = patch().getValue("atStart").jsonPrimitive.boolean
    private fun String.ids(field: String) =
        patch().getValue(field).jsonArray.map { it.jsonObject.getValue("localId").jsonPrimitive.long }
    private fun String.dropped() = patch().getValue("drop").jsonArray.map { it.jsonPrimitive.long }

    /** Every body the patch carries, whichever list it is in: the payload's weight. */
    private fun String.bodies() = ids("prepend").size + ids("append").size + ids("update").size

    @Test
    fun aChannelNeverShownBeforeIsSentInFull() {
        val sync = TranscriptSync()
        val calls = sync.update("alpha", transcript(3))

        assertEquals(1, calls.applies().size)
        assertTrue(calls.applies()[0].full(), calls.applies()[0])
        assertEquals(listOf(1L, 2L, 3L), calls.applies()[0].ids("append"))
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

        assertFalse(apply.full(), apply)
        assertEquals(listOf(201L), apply.ids("append"), "only the arriving row should carry a body")
        assertEquals(1, apply.bodies())
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

        assertEquals(listOf(21L), apply.ids("update"))
        assertEquals(1, apply.bodies())
    }

    @Test
    fun aDeliveryChangeCountsAsAChange() {
        val sync = TranscriptSync()
        sync.update("alpha", listOf(msg(1, "hi", Delivery.Sending)))

        val apply = sync.update("alpha", listOf(msg(1, "hi", Delivery.Sent))).applies().single()
        assertEquals(listOf(1L), apply.ids("update"))
    }

    /** Trimming at the buffer cap drops ids from the front and adds none. */
    @Test
    fun aTrimmedFrontIsADropAlone() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(5))

        val apply = sync.update("alpha", transcript(5).drop(1)).applies().single()
        assertEquals(0, apply.bodies(), "dropping rows should not resend any body")
        assertEquals(listOf(1L), apply.dropped())
    }

    /** A filter taking rows out of the middle — joins and leaves hidden — is a drop too. */
    @Test
    fun rowsRemovedFromTheMiddleAreDropped() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(5))

        val apply = sync.update("alpha", transcript(5).filter { it.localId != 3L }).applies().single()
        assertEquals(listOf(3L), apply.dropped())
        assertEquals(0, apply.bodies())
    }

    /** And putting them back is a full redraw rather than a guess about where they go. */
    @Test
    fun rowsReappearingInTheMiddleRedrawTheWindow() {
        val sync = TranscriptSync()
        sync.update("alpha", transcript(5).filter { it.localId != 3L })

        val apply = sync.update("alpha", transcript(5)).applies().single()
        assertTrue(apply.full(), apply)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), apply.ids("append"))
    }

    // --- the drawn window --------------------------------------------------

    /**
     * The point of the window: a long history costs what is drawn, not what is
     * kept. Ten thousand lines open as the newest few hundred.
     */
    @Test
    fun aLongHistoryOpensAsTheNewestWindow() {
        val sync = TranscriptSync(window = 300)
        val apply = sync.update("alpha", transcript(10_000)).applies().single()

        assertEquals((9_701L..10_000L).toList(), apply.ids("append"))
        assertFalse(apply.atStart(), "there is older history to scroll into")
    }

    @Test
    fun aShortHistorySaysItIsAtTheStart() {
        val sync = TranscriptSync(window = 300)
        assertTrue(sync.update("alpha", transcript(10)).applies().single().atStart())
    }

    /** Arrivals at the bottom grow the window until the slack runs out, then it is cut. */
    @Test
    fun aPinnedReaderHasTheTopCutBackOnceTheSlackIsUsed() {
        val sync = TranscriptSync(window = 10, slack = 5)
        sync.update("alpha", transcript(100))

        // Up to window + slack rows drawn, nothing is dropped.
        for (n in 101..105) {
            assertEquals(emptyList(), sync.update("alpha", transcript(n)).applies().single().dropped())
        }
        // One past it, and the top goes back down to the window.
        val cut = sync.update("alpha", transcript(106)).applies().single()
        assertEquals((91L..96L).toList(), cut.dropped())
        assertEquals(listOf(106L), cut.ids("append"))
    }

    /** A reader up in the history must not have it cut out from under them. */
    @Test
    fun anUnpinnedReaderKeepsTheirWindow() {
        val sync = TranscriptSync(window = 10, slack = 5)
        sync.update("alpha", transcript(100))
        sync.setPinned("alpha", false)

        for (n in 101..110) sync.update("alpha", transcript(n))
        val apply = sync.update("alpha", transcript(111)).applies().single()
        assertEquals(emptyList(), apply.dropped())

        // Back at the bottom, the next redraw cuts it down.
        sync.setPinned("alpha", true)
        val cut = sync.update("alpha", transcript(111)).applies().single()
        assertEquals((91L..101L).toList(), cut.dropped())
        assertEquals(0, cut.bodies())
    }

    /** Scrolling up asks for older rows a chunk at a time, oldest first, above the rest. */
    @Test
    fun olderPrependsAChunk() {
        val sync = TranscriptSync(window = 10, chunk = 4)
        val history = transcript(20)
        sync.update("alpha", history)

        val first = sync.older("alpha", history).single()
        assertEquals(listOf(7L, 8L, 9L, 10L), first.ids("prepend"))
        assertFalse(first.atStart())

        sync.older("alpha", history)
        val last = sync.older("alpha", history).single()
        assertEquals(listOf(1L, 2L), last.ids("prepend"))
        assertTrue(last.atStart())

        assertEquals(emptyList(), sync.older("alpha", history), "nothing older than the start")
    }

    /** What was prepended is part of the window: an arrival afterwards is still one row. */
    @Test
    fun anArrivalAfterOlderIsStillOneRow() {
        val sync = TranscriptSync(window = 10, slack = 100, chunk = 4)
        sync.update("alpha", transcript(20))
        sync.older("alpha", transcript(20))

        val apply = sync.update("alpha", transcript(21)).applies().single()
        assertEquals(listOf(21L), apply.ids("append"))
        assertEquals(emptyList(), apply.dropped())
    }

    /** A channel that piled up while hidden comes back at its latest, not all of it drawn. */
    @Test
    fun aFloodWhileAwayIsNotAllDrawn() {
        val sync = TranscriptSync(window = 10, slack = 5)
        sync.update("alpha", transcript(10))
        sync.update("beta", transcript(1))
        sync.setPinned("alpha", false)

        val apply = sync.update("alpha", transcript(1_000)).applies().single()
        assertEquals((991L..1_000L).toList(), apply.ids("append"))
        assertEquals((1L..10L).toList(), apply.dropped())
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
        assertTrue(apply.full(), "an evicted channel must not be patched: $apply")
        assertEquals(3, apply.bodies())
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
        assertTrue(calls.applies().single().full())
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
            .jsonObject.getValue("append")
            .jsonArray[0]
            .jsonObject.getValue("text")
            .jsonPrimitive.content

        assertEquals(nasty, text, "message text did not survive as data")
    }
}
