package chat.hc.core.render

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One of hack.chat's colour schemes, reduced to the values the *native* chrome
 * needs so the app around the WebView matches the transcript inside it.
 *
 * The WebView loads the scheme's CSS directly; these fields are extracted from
 * the very same stylesheets at build time by `tools/gen-schemes.mjs`, so the
 * two cannot drift apart by hand.
 */
@Serializable
data class Scheme(
    val name: String,
    val label: String,
    val background: String,
    val foreground: String,
    val nick: String,
    val link: String,
    val warn: String,
    val dark: Boolean,
)

@Serializable
private data class SchemeFile(val schemes: List<Scheme>)

object Schemes {
    /** Matches the site's own defaults. */
    const val DEFAULT_SCHEME = "default"
    const val DEFAULT_HIGHLIGHT = "hybrid"

    /** Highlight themes shipped in `assets/renderer/vendor/hljs/styles`. */
    val highlightThemes = listOf(
        "agate", "androidstudio", "atom-one-dark", "darcula", "github",
        "hybrid", "rainbow", "tk-night", "tomorrow", "xcode", "zenburn",
    )

    val fallback = Scheme(
        name = DEFAULT_SCHEME,
        label = "Default",
        background = "#151515",
        foreground = "#d0d0d0",
        nick = "#6a9fb5",
        link = "#e0e0e0",
        warn = "#f4bf75",
        dark = true,
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses `assets/renderer/schemes.json`; never throws. */
    fun parse(text: String): List<Scheme> = runCatching {
        json.decodeFromString(SchemeFile.serializer(), text).schemes
    }.getOrElse { emptyList() }.ifEmpty { listOf(fallback) }
}
