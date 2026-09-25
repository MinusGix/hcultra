package chat.hc.ultra.ui

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import chat.hc.core.render.FontScale
import chat.hc.core.render.ImageHosts
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes
import chat.hc.core.session.Servers
import chat.hc.ultra.data.ImageSources

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

/**
 * The renderer stylesheet's `--hc-base`, in the unit Compose measures text in.
 *
 * Native text that is meant to match the transcript — the composer, the size
 * sample in settings — starts here and multiplies by the same scale the page
 * does, so the two halves cannot drift apart by a point or two.
 */
const val BASE_TEXT_SP = 15f

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

/**
 * How the transcript is presented: colours, layout, and what it shows at all.
 *
 * Plain prefs — nothing here is sensitive — and read from the service as well
 * as the Activity, since some of it decides what is worth keeping rather than
 * only how it looks.
 */
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

    /**
     * Whether `![](…)` images embed inline instead of showing as a link.
     *
     * Off by default, as on the site. Fetching an image tells the host serving
     * it that you are here, and the person who chose the URL picked which host
     * that is; that is a thing to opt into rather than out of. Only hack.chat's
     * own [chat.hc.core.render.ImageHosts] can be embedded either way.
     */
    var allowImages: Boolean
        get() = prefs.getBoolean(KEY_IMAGES, false)
        set(value) = prefs.edit().putBoolean(KEY_IMAGES, value).apply()

    /**
     * Image sources beyond the site's, as canonical https URL prefixes (see
     * [ImageHosts.normalizePrefix]). Only consulted with [allowImages] on.
     *
     * Absent means never edited, and reads as [ImageHosts.defaultExtra]; an
     * emptied list is stored as such and stays empty. Entries are re-normalized
     * on the way out, so a hand-edited or older file cannot smuggle in
     * something the settings field would have refused. Reading or writing also
     * updates [ImageSources], which is what the request gates consult.
     */
    var extraImageSources: List<String>
        get() {
            val stored = prefs.getString(KEY_EXTRA_IMAGES, null)
            val list = stored?.lines()?.mapNotNull(ImageHosts::normalizePrefix)?.distinct()
                ?: ImageHosts.defaultExtra
            ImageSources.extra = list
            return list
        }
        set(value) {
            val list = value.mapNotNull(ImageHosts::normalizePrefix).distinct()
            ImageSources.extra = list
            prefs.edit().putString(KEY_EXTRA_IMAGES, list.joinToString("\n")).apply()
        }

    /**
     * Whether someone arriving or leaving gets a line in the transcript — the
     * site's "Join/left notify", on by default there and here.
     *
     * Suppression happens in the service, before the message is buffered, so
     * turning this off also keeps a busy channel's join spam from evicting real
     * messages: history lives only in memory and the server cannot re-serve it.
     * The user list is unaffected.
     */
    var joinLeave: Boolean
        get() = prefs.getBoolean(KEY_JOIN_LEAVE, true)
        set(value) = prefs.edit().putBoolean(KEY_JOIN_LEAVE, value).apply()

    /**
     * How large the transcript's text is, as a multiplier on the renderer's
     * base size.
     *
     * Snapped on the way out rather than trusted: the stored value is only ever
     * a rung of [FontScale.STEPS], and reading it that way means a file left by
     * a build with a different ladder still lands somewhere the buttons can
     * move away from.
     */
    var fontScale: Float
        get() = FontScale.snap(prefs.getFloat(KEY_FONT_SCALE, FontScale.DEFAULT))
        set(value) = prefs.edit().putFloat(KEY_FONT_SCALE, FontScale.snap(value)).apply()

    private companion object {
        const val KEY_SCHEME = "scheme"
        const val KEY_HIGHLIGHT = "highlight"
        const val KEY_LAYOUT = "nick_layout"
        const val KEY_IMAGES = "allow_images"
        const val KEY_EXTRA_IMAGES = "extra_image_sources"
        const val KEY_JOIN_LEAVE = "join_leave"
        const val KEY_FONT_SCALE = "font_scale"
    }
}
