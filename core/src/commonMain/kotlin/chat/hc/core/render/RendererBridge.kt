package chat.hc.core.render

import chat.hc.core.store.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Wire format between the native layer and the WebView renderer.
 *
 * Lives in core rather than the Android module so iOS reuses it verbatim: the
 * renderer assets and this payload are the same on both platforms, and only the
 * WebView/WKWebView host differs.
 */
@Serializable
internal data class WireMessage(
    val localId: Long,
    val kind: String,
    val nick: String,
    val text: String,
    val trip: String? = null,
    val color: String? = null,
    /** Server-assigned nick decoration; an arbitrary string, not an enum. */
    val flair: String? = null,
    /** Drives the .admin/.mod scheme classes. */
    val level: Int = 0,
    val delivery: String,
    val isMine: Boolean,
)

/**
 * One channel's transcript, as far as the page needs to know.
 *
 * [order] is every id in transcript order and [upsert] carries bodies only for
 * the rows that are new or have changed — which is the whole point: ids are a
 * few bytes each, while a row with maths and a code block is not. The page
 * rebuilds ordering and removals from [order] alone and trusts [upsert] for the
 * rest, because [TranscriptSync] is what knows the difference.
 *
 * [full] means the page must clear this channel before applying: it is how a
 * channel it has never held, or has dropped, gets rebuilt from nothing.
 */
@Serializable
internal data class WirePatch(
    val full: Boolean,
    val order: List<Long>,
    val upsert: List<WireMessage>,
)

object RendererBridge {

    private val json = Json { encodeDefaults = true }

    private fun wire(m: ChatMessage) = WireMessage(
        localId = m.localId,
        kind = m.kind.name,
        nick = m.nick,
        text = m.text,
        trip = m.trip?.takeIf(String::isNotBlank),
        color = m.color,
        flair = m.flair?.takeIf(String::isNotBlank),
        level = m.level,
        delivery = m.delivery.name,
        isMine = m.isMine,
    )

    /** The message list as a JSON array. */
    fun encode(messages: List<ChatMessage>): String = json.encodeToString(
        ListSerializer(WireMessage.serializer()),
        messages.map(::wire),
    )

    /**
     * A complete `HC.apply(...)` statement, safe to hand to `evaluateJavascript`.
     *
     * Both arguments are passed as JSON *string literals* and parsed inside the
     * page rather than interpolated as code. Message text is fully untrusted —
     * anyone in a channel can send anything — so it must never be able to
     * terminate the enclosing expression. The channel name goes the same way:
     * it is server-supplied too.
     */
    fun applyCall(
        channel: String,
        full: Boolean,
        order: List<Long>,
        upsert: List<ChatMessage>,
    ): String {
        val patch = json.encodeToString(
            WirePatch.serializer(),
            WirePatch(full = full, order = order, upsert = upsert.map(::wire)),
        )
        return "HC.apply(${quote(channel)}, ${quote(patch)});"
    }

    /** Brings a channel's container to the front; the page renders nothing new. */
    fun showCall(channel: String): String = "HC.show(${quote(channel)});"

    /** Drops a channel's DOM. Paired with [TranscriptSync] forgetting it. */
    fun evictCall(channel: String): String = "HC.evict(${quote(channel)});"

    private fun quote(s: String): String = json.encodeToString(String.serializer(), s)

    /**
     * Switches the hack.chat colour scheme and highlight.js theme, both of
     * which are stylesheet swaps inside the page.
     */
    fun themeCall(scheme: String, highlight: String): String {
        val s = json.encodeToString(String.serializer(), scheme)
        val h = json.encodeToString(String.serializer(), highlight)
        return "HC.setTheme($s, $h);"
    }

    /**
     * Turns inline images on or off, and says which extra URL prefixes (beyond
     * the site's hosts) count as image sources.
     *
     * The renderer rebuilds the transcript when this changes: whether a message
     * shows an image is not part of the message, so the render diff would
     * otherwise leave everything already on screen as it was.
     */
    fun allowImagesCall(allow: Boolean, extra: List<String> = emptyList()): String =
        "HC.setAllowImages($allow, ${json.encodeToString(ListSerializer(String.serializer()), extra)});"

    /** Back to the newest message, and stay there as more arrive. */
    fun scrollToBottomCall(): String = "HC.scrollToBottom();"

    /**
     * Scales the transcript's text.
     *
     * One number, written to the page's root font size; the stylesheet keeps
     * every other size in `em`, so paddings, gutters and the code font follow
     * it rather than needing a call each. Snapped on the way out, because the
     * page has no ladder of its own and would honour whatever it was handed.
     */
    fun fontScaleCall(scale: Float): String = "HC.setFontScale(${FontScale.snap(scale)});"

    /**
     * Switches how a message's nick and trip sit relative to its text.
     *
     * A class on the document rather than three different DOM shapes: the
     * scheme stylesheets target `.message` / `.nick` / `.trip`, so the markup
     * has to stay constant across all three layouts or 44 stylesheets would
     * need to know about them.
     */
    fun layoutCall(layout: NickLayout): String {
        val l = json.encodeToString(String.serializer(), layout.cssClass)
        return "HC.setLayout($l);"
    }
}

/** How a message's nick and trip sit relative to its text. */
enum class NickLayout(val cssClass: String, val label: String) {
    /** Nick, trip and text on one run, as the transcript has always been. */
    Inline("layout-inline", "Inline"),

    /** Nicks in a fixed gutter, text in a column beside it — closest to the site. */
    Gutter("layout-gutter", "Name column"),

    /** `trip nick` on its own line, text beneath it. */
    Stacked("layout-stacked", "Stacked"),
    ;

    companion object {
        val DEFAULT = Inline

        fun from(name: String?): NickLayout =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
