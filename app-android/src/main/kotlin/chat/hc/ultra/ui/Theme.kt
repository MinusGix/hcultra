package chat.hc.ultra.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes
import chat.hc.core.session.Servers

/** Reads the build-time scheme metadata shipped alongside the renderer. */
object SchemeAssets {
    private var cache: List<Scheme>? = null

    fun load(context: Context): List<Scheme> = cache ?: Schemes.parse(
        runCatching {
            context.assets.open("renderer/schemes.json").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    ).also { cache = it }
}

private fun String.toColor(fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(this))
}.getOrDefault(fallback)

/**
 * The scheme's background as a plain ARGB int.
 *
 * Used to paint the window *before* Compose runs. Without it the activity shows
 * one frame of whatever `themes.xml` hardcoded — which cannot know the chosen
 * scheme — and a light scheme visibly flashes dark on launch.
 */
fun Scheme.windowBackgroundArgb(): Int = background.toColor(Color(0xFF151515)).toArgb()

/** The named scheme, falling back to the first if the stored name is unknown. */
fun List<Scheme>.resolve(name: String): Scheme = firstOrNull { it.name == name } ?: first()

/**
 * Builds a Material colour scheme from a hack.chat scheme so the native chrome
 * sits alongside the transcript rather than clashing with it.
 */
fun Scheme.toColorScheme(): ColorScheme {
    val bg = background.toColor(Color(0xFF151515))
    val fg = foreground.toColor(Color(0xFFD0D0D0))
    val accent = nick.toColor(fg)
    val error = warn.toColor(Color(0xFFF4BF75))

    // Surfaces lift slightly off the background so the composer reads as a
    // distinct element without introducing a colour the scheme never chose.
    val surface = if (dark) bg.lighten(0.06f) else bg.darken(0.05f)

    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = if (accent.isLight()) Color.Black else Color.White,
        secondary = accent,
        background = bg,
        onBackground = fg,
        surface = surface,
        onSurface = fg,
        surfaceVariant = surface,
        onSurfaceVariant = fg.copy(alpha = 0.75f),
        outline = fg.copy(alpha = 0.4f),
        error = error,
        onError = if (error.isLight()) Color.Black else Color.White,
    )
}

private fun Color.lighten(amount: Float) = Color(
    red = (red + amount).coerceAtMost(1f),
    green = (green + amount).coerceAtMost(1f),
    blue = (blue + amount).coerceAtMost(1f),
    alpha = alpha,
)

private fun Color.darken(amount: Float) = Color(
    red = (red - amount).coerceAtLeast(0f),
    green = (green - amount).coerceAtLeast(0f),
    blue = (blue - amount).coerceAtLeast(0f),
    alpha = alpha,
)

private fun Color.isLight(): Boolean =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue) > 0.5f

/** Persisted server endpoint. Plain prefs: an address is not a secret. */
class ServerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hc_server", Context.MODE_PRIVATE)

    var url: String
        get() = prefs.getString(KEY_URL, Servers.DEFAULT_URL) ?: Servers.DEFAULT_URL
        set(value) = prefs.edit().putString(KEY_URL, value).apply()

    private companion object {
        const val KEY_URL = "url"
    }
}

/** Persisted theme choice. Plain prefs: nothing here is sensitive. */
class ThemePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hc_theme", Context.MODE_PRIVATE)

    var scheme: String
        get() = prefs.getString(KEY_SCHEME, Schemes.DEFAULT_SCHEME) ?: Schemes.DEFAULT_SCHEME
        set(value) = prefs.edit().putString(KEY_SCHEME, value).apply()

    /**
     * Explicit highlight-theme choice, or null to follow the scheme's paired
     * default. Stored separately from the effective value so that switching
     * scheme keeps moving the highlight along with it until the user pins one.
     */
    var highlightOverride: String?
        get() = prefs.getString(KEY_HIGHLIGHT, null)
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_HIGHLIGHT) else putString(KEY_HIGHLIGHT, value)
        }.apply()

    /** How a message's nick and trip sit relative to its text. */
    var nickLayout: NickLayout
        get() = NickLayout.from(prefs.getString(KEY_LAYOUT, null))
        set(value) = prefs.edit().putString(KEY_LAYOUT, value.name).apply()

    private companion object {
        const val KEY_SCHEME = "scheme"
        const val KEY_HIGHLIGHT = "highlight"
        const val KEY_LAYOUT = "nick_layout"
    }
}
