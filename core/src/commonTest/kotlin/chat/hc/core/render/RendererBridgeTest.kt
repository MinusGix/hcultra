package chat.hc.core.render

import chat.hc.core.session.IgnoreMode
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The statements handed to the page. Message text, nicks and channel names are
 * all chosen by strangers, so the one property that matters most is that
 * nothing in them can end the statement it travels in.
 */
class RendererBridgeTest {

    private fun msg(
        text: String = "hi",
        nick: String = "bob",
        trip: String? = null,
        flair: String? = null,
        userid: Long = 0L,
        at: Long = 0L,
    ) = ChatMessage(
        localId = 1, kind = MessageKind.Chat, nick = nick, userid = userid, trip = trip,
        text = text, flair = flair, at = at, delivery = Delivery.Sent,
    )

    /**
     * Reads `Name(arg, arg, …);` the way a JS parser would see it if every
     * argument is a JSON value: as one array. Anything that escaped its
     * string literal would no longer parse as one.
     */
    private fun String.arguments(name: String): List<kotlinx.serialization.json.JsonElement> {
        assertTrue(startsWith("$name(") && endsWith(");"), this)
        return Json.parseToJsonElement("[" + removePrefix("$name(").removeSuffix(");") + "]").jsonArray
    }

    private fun String.patch(): JsonObject {
        val (_, patch) = arguments("HC.apply")
        return Json.parseToJsonElement(patch.jsonPrimitive.content).jsonObject
    }

    private fun apply(channel: String, append: List<ChatMessage>) = RendererBridge.applyCall(
        channel = channel, full = false, drop = emptyList(), prepend = emptyList(),
        append = append, update = emptyList(), atStart = false,
    )

    @Test
    fun hostileTextStaysInsideItsString() {
        val hostile = listOf(
            """"); alert(1); ("""",
            """'); alert(1); (\'""",
            "</script><script>alert(1)</script>",
            "back\\slash \u0000 nul \n newline   separator",
        )
        hostile.forEach { text ->
            val call = apply(channel = text, append = listOf(msg(text = text, nick = text)))
            val (channel, _) = call.arguments("HC.apply")
            assertEquals(text, channel.jsonPrimitive.content)
            val row = call.patch().getValue("append").jsonArray.single().jsonObject
            assertEquals(text, row.getValue("text").jsonPrimitive.content)
            assertEquals(text, row.getValue("nick").jsonPrimitive.content)
        }
    }

    /** Held nicks hand both back to native for the menu, so every row carries them. */
    @Test
    fun rowsCarryTheUseridAndTime() {
        val row = apply("c", listOf(msg(userid = 7, at = 1_700_000_000_000))).patch()
            .getValue("append").jsonArray.single().jsonObject
        assertEquals(7, row.getValue("userid").jsonPrimitive.long)
        assertEquals(1_700_000_000_000, row.getValue("at").jsonPrimitive.long)
    }

    /** A blank trip or flair is none, not an empty badge for the page to draw. */
    @Test
    fun blankTripAndFlairAreNone() {
        val row = Json.parseToJsonElement(RendererBridge.encode(listOf(msg(trip = " ", flair = "")))).jsonArray
            .single().jsonObject
        assertEquals(JsonNull, row["trip"])
        assertEquals(JsonNull, row["flair"])

        val kept = Json.parseToJsonElement(RendererBridge.encode(listOf(msg(trip = "t5dInH", flair = "⭐")))).jsonArray
            .single().jsonObject
        assertEquals("t5dInH", kept.getValue("trip").jsonPrimitive.content)
        assertEquals("⭐", kept.getValue("flair").jsonPrimitive.content)
    }

    @Test
    fun patchFieldsArriveAsGiven() {
        val call = RendererBridge.applyCall(
            channel = "c", full = true, drop = listOf(3, 4), prepend = listOf(msg()),
            append = emptyList(), update = emptyList(), atStart = true,
        )
        val patch = call.patch()
        assertTrue(patch.getValue("full").jsonPrimitive.boolean)
        assertTrue(patch.getValue("atStart").jsonPrimitive.boolean)
        assertEquals(listOf(3L, 4L), patch.getValue("drop").jsonArray.map { it.jsonPrimitive.long })
        assertEquals(1, patch.getValue("prepend").jsonArray.size)
    }

    @Test
    fun channelCallsQuoteTheirChannel() {
        val name = """a"); evil(""""
        assertEquals(name, RendererBridge.showCall(name).arguments("HC.show").single().jsonPrimitive.content)
        assertEquals(name, RendererBridge.evictCall(name).arguments("HC.evict").single().jsonPrimitive.content)
    }

    @Test
    fun translationIsAJsonStringTheChannelAndIdBeside() {
        val call = RendererBridge.translationCall("c", 42, text = """"); evil(""""", note = "Translated from Spanish")
        val (channel, id, payload) = call.arguments("HC.translation")
        assertEquals("c", channel.jsonPrimitive.content)
        assertEquals(42, id.jsonPrimitive.long)
        val t = Json.parseToJsonElement(payload.jsonPrimitive.content).jsonObject
        assertEquals(""""); evil(""""", t.getValue("text").jsonPrimitive.content)
        assertEquals("Translated from Spanish", t.getValue("note").jsonPrimitive.content)
        assertEquals(false, t.getValue("failed").jsonPrimitive.boolean)
    }

    @Test
    fun imageSourcesAreAJsonList() {
        val call = RendererBridge.allowImagesCall(true, listOf("https://a.example/", """x"]);evil(""""))
        val (allow, extra) = call.arguments("HC.setAllowImages")
        assertTrue(allow.jsonPrimitive.boolean)
        assertEquals(listOf("https://a.example/", """x"]);evil(""""), extra.jsonArray.map { it.jsonPrimitive.content })
    }

    /** A veiled row says so; every other row says it is not. */
    @Test
    fun rowsCarryWhetherTheyAreVeiled() {
        val rows = apply("c", listOf(msg(), msg().copy(veiled = true))).patch().getValue("append").jsonArray
        assertEquals(listOf(false, true), rows.map { it.jsonObject.getValue("veiled").jsonPrimitive.boolean })
    }

    /** The replacement line is the user's, but goes as a string all the same. */
    @Test
    fun theIgnoreModeAndItsTextAreQuoted() {
        val text = """"); evil("""
        val (mode, sent) = RendererBridge.ignoreModeCall(IgnoreMode.Fun, text).arguments("HC.setIgnoreMode")
        assertEquals("Fun", mode.jsonPrimitive.content)
        assertEquals(text, sent.jsonPrimitive.content)
    }

    @Test
    fun anUnknownSavedIgnoreModeIsTheDefault() {
        assertEquals(IgnoreMode.Hide, IgnoreMode.from("Explode"))
        assertEquals(IgnoreMode.Hide, IgnoreMode.from(null))
        IgnoreMode.entries.forEach { assertEquals(it, IgnoreMode.from(it.name)) }
    }

    @Test
    fun themeNamesAreQuoted() {
        val (scheme, highlight) = RendererBridge.themeCall("atelier\"dune", "github").arguments("HC.setTheme")
        assertEquals("atelier\"dune", scheme.jsonPrimitive.content)
        assertEquals("github", highlight.jsonPrimitive.content)
    }

    // ---- layouts ----

    @Test
    fun aLayoutIsSentAsItsClass() {
        NickLayout.entries.forEach { layout ->
            val (cls) = RendererBridge.layoutCall(layout).arguments("HC.setLayout")
            assertEquals(layout.cssClass, cls.jsonPrimitive.content)
        }
        assertEquals("""HC.setLayout("layout-aligned");""", RendererBridge.layoutCall(NickLayout.Aligned))
    }

    /** The class is a body class; two layouts sharing one would be one layout. */
    @Test
    fun everyLayoutHasItsOwnClass() {
        assertEquals(NickLayout.entries.size, NickLayout.entries.map { it.cssClass }.toSet().size)
    }

    /** A setting saved by a build that had a layout this one does not falls back, rather than failing. */
    @Test
    fun anUnknownSavedLayoutIsTheDefault() {
        assertEquals(NickLayout.Aligned, NickLayout.from("Aligned"))
        assertEquals(NickLayout.DEFAULT, NickLayout.from(null))
        assertEquals(NickLayout.DEFAULT, NickLayout.from("Sideways"))
        assertEquals(NickLayout.DEFAULT, NickLayout.from("layout-aligned"), "stored by name, not by class")
    }
}
