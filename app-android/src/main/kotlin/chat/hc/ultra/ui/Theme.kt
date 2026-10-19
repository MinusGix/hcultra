package chat.hc.ultra.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import chat.hc.core.render.FontScale
import chat.hc.core.render.ImageHosts
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.Schemes
import chat.hc.core.render.TranscriptFont
import chat.hc.core.session.Dialect
import chat.hc.core.session.IgnoreMode
import chat.hc.core.session.Servers
import chat.hc.core.store.HistoryLimit
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

/**
 * The face Compose draws a [TranscriptFont] in, for the same native text
 * [BASE_TEXT_SP] is for. Condensed is looked up by the alias the WebView finds
 * it by, so both halves get the same Roboto Condensed.
 */
val TranscriptFont.composeFamily: FontFamily
    get() = when (this) {
        TranscriptFont.System -> FontFamily.Default
        TranscriptFont.Serif -> FontFamily.Serif
        TranscriptFont.Monospace -> FontFamily.Monospace
        TranscriptFont.Condensed -> condensedFamily
    }

private val condensedFamily: FontFamily by lazy {
    FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
}

/** A server the user joins channels on, and the protocol to speak to it. */
data class ServerEntry(val url: String, val dialect: Dialect = Dialect.Auto)

/**
 * The servers the user has added, in the order they added them. Plain prefs:
 * an address is not a secret.
 *
 * Never empty — the join form has to offer somewhere — and hack.chat is the
 * one there is until the user says otherwise.
 */
class ServerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hc_server", Context.MODE_PRIVATE)

    var servers: List<ServerEntry>
        get() = prefs.getString(KEY_SERVERS, null)
            ?.split(RECORD)
            ?.mapNotNull(::decode)
            ?.takeIf { it.isNotEmpty() }
            // Before there was a list there was one address; it becomes the first.
            ?: listOf(ServerEntry(prefs.getString(KEY_URL, null) ?: Servers.DEFAULT_URL))
        set(value) = prefs.edit()
            .putString(KEY_SERVERS, value.distinctBy { it.url }.joinToString(RECORD) { "${it.url}$FIELD${it.dialect.name}" })
            .apply()

    /** Read per connection by the service, so a change applies to the next one. */
    fun dialectFor(url: String): Dialect = servers.firstOrNull { it.url == url }?.dialect ?: Dialect.Auto

    /** Where the join form starts: wherever the user last joined. */
    var lastUsed: String
        get() = prefs.getString(KEY_LAST, null)?.takeIf { url -> servers.any { it.url == url } }
            ?: servers.first().url
        set(value) = prefs.edit().putString(KEY_LAST, value).apply()

    private fun decode(raw: String): ServerEntry? {
        val f = raw.split(FIELD)
        val url = f[0].takeIf(String::isNotEmpty) ?: return null
        return ServerEntry(url, f.getOrNull(1)?.let { name -> Dialect.entries.firstOrNull { it.name == name } } ?: Dialect.Auto)
    }

    private companion object {
        /** The single address of builds before the list; read once, to migrate. */
        const val KEY_URL = "url"
        const val KEY_SERVERS = "servers"
        const val KEY_LAST = "last_used"
        // Control characters, as ChannelHistory uses: no URL can contain them.
        const val FIELD = "\u0001"
        const val RECORD = "\u0002"
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

    /** What the transcript does with an ignored sender's lines. */
    var ignoreMode: IgnoreMode
        get() = IgnoreMode.from(prefs.getString(KEY_IGNORE_MODE, null))
        set(value) = prefs.edit().putString(KEY_IGNORE_MODE, value.name).apply()

    /**
     * What [IgnoreMode.Fun] puts in place of their lines. Blank reads as the
     * default, so clearing the field is how to get it back.
     */
    var ignoreFunText: String
        get() = prefs.getString(KEY_IGNORE_TEXT, null)?.takeIf(String::isNotBlank) ?: IgnoreMode.DEFAULT_FUN_TEXT
        set(value) = prefs.edit().putString(KEY_IGNORE_TEXT, value).apply()

    /**
     * How many lines each channel keeps. Any positive number; the renderer
     * draws only a window of it, so a large one costs memory, not speed.
     */
    var historyLines: Int
        get() = prefs.getInt(KEY_HISTORY_LINES, HistoryLimit.DEFAULT_LINES).coerceAtLeast(1)
        set(value) = prefs.edit().putInt(KEY_HISTORY_LINES, value.coerceAtLeast(1)).apply()

    /** How many days of each channel to keep, or null to keep any age up to [historyLines]. */
    var historyDays: Int?
        get() = prefs.getInt(KEY_HISTORY_DAYS, 0).takeIf { it > 0 }
        set(value) = prefs.edit().putInt(KEY_HISTORY_DAYS, value?.coerceAtLeast(1) ?: 0).apply()

    /** The two history settings as the service applies them. */
    val historyLimit: HistoryLimit
        get() = HistoryLimit(historyLines, historyDays?.let { it * HistoryLimit.DAY_MILLIS })

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

    /**
     * Whether code blocks wrap long lines rather than scrolling sideways. On by
     * default: on a phone, a sideways scroll inside a vertically scrolling
     * transcript is the harder of the two to read.
     */
    var wrapCode: Boolean
        get() = prefs.getBoolean(KEY_WRAP_CODE, true)
        set(value) = prefs.edit().putBoolean(KEY_WRAP_CODE, value).apply()

    /** The transcript's typeface. Stored by name, so a reordered list keeps everyone's choice. */
    var font: TranscriptFont
        get() = TranscriptFont.from(prefs.getString(KEY_FONT, null))
        set(value) = prefs.edit().putString(KEY_FONT, value.name).apply()

    private companion object {
        const val KEY_SCHEME = "scheme"
        const val KEY_HIGHLIGHT = "highlight"
        const val KEY_LAYOUT = "nick_layout"
        const val KEY_IMAGES = "allow_images"
        const val KEY_EXTRA_IMAGES = "extra_image_sources"
        const val KEY_JOIN_LEAVE = "join_leave"
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_WRAP_CODE = "wrap_code"
        const val KEY_FONT = "font_family"
        const val KEY_IGNORE_MODE = "ignore_mode"
        const val KEY_IGNORE_TEXT = "ignore_fun_text"
        const val KEY_HISTORY_LINES = "history_lines"
        const val KEY_HISTORY_DAYS = "history_days"
    }
}
