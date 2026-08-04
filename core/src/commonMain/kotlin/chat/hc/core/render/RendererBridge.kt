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
    /** Drives the .admin/.mod scheme classes. */
    val level: Int = 0,
    val delivery: String,
    val isMine: Boolean,
    val streamComplete: Boolean,
)

object RendererBridge {

    private val json = Json { encodeDefaults = true }

    /** The message list as a JSON array. */
    fun encode(messages: List<ChatMessage>): String = json.encodeToString(
        ListSerializer(WireMessage.serializer()),
        messages.map {
            WireMessage(
                localId = it.localId,
                kind = it.kind.name,
                nick = it.nick,
                text = it.text,
                trip = it.trip?.takeIf(String::isNotBlank),
                color = it.color,
                level = it.level,
                delivery = it.delivery.name,
                isMine = it.isMine,
                streamComplete = it.streamComplete,
            )
        },
    )

    /**
     * A complete `HC.render(...)` statement, safe to hand to `evaluateJavascript`.
     *
     * The payload is passed as a JSON *string literal* and parsed inside the
     * page rather than interpolated as code. Message text is fully untrusted —
     * anyone in a channel can send anything — so it must never be able to
     * terminate the enclosing expression.
     */
    fun renderCall(messages: List<ChatMessage>): String {
        val payload = json.encodeToString(String.serializer(), encode(messages))
        return "HC.render($payload);"
    }

    /**
     * Switches the hack.chat colour scheme and highlight.js theme, both of
     * which are stylesheet swaps inside the page.
     */
    fun themeCall(scheme: String, highlight: String): String {
        val s = json.encodeToString(String.serializer(), scheme)
        val h = json.encodeToString(String.serializer(), highlight)
        return "HC.setTheme($s, $h);"
    }
}
