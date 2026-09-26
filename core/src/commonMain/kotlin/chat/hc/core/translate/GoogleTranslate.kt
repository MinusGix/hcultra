package chat.hc.core.translate

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A message in another language. */
data class Translation(
    val text: String,
    /** The language Google decided the original was in, as its code — `es`, `zh-CN`. */
    val from: String,
)

/**
 * Google Translate's keyless endpoint, the one its own browser widgets use.
 *
 * Unofficial: there is no key, no quota anyone promised, and nothing stopping
 * Google changing the response shape or refusing clients like this one. That is
 * the trade for a translation that costs nothing and needs no server of ours,
 * and it is why [parse] treats anything it does not recognise as "no answer"
 * rather than guessing.
 *
 * Lives in core, apart from the request, for the same reason as
 * [chat.hc.core.update.Releases]: the reading of a positional array whose
 * meaning nobody documents is the part that can quietly go wrong.
 */
object GoogleTranslate {

    private val json = Json

    /**
     * Longer than this is refused rather than sent. The endpoint takes more,
     * but nothing obliges it to keep doing so, and a wall of bot output is not
     * what anyone taps Translate for.
     */
    const val MAX_CHARS = 5000

    /**
     * Where to POST the text, as a form field `q`, to have it translated into
     * [target]. POST rather than GET so the message is not in a URL — URLs end
     * up in logs that bodies do not — and so length is not bounded by one.
     *
     * `sl=auto` has Google detect the source; `dt=t` asks for the translation
     * and nothing else (no transliteration, dictionary or alternatives).
     */
    fun url(target: String): String =
        "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$target&dt=t"

    /**
     * The translation in a response body, or null if the body is not one.
     *
     * The shape is `[[[translated, original, …], …], null, "detected", …]`:
     * one entry per sentence, whose first element is that sentence translated.
     * Newlines ride along inside the sentences, so joining them with nothing
     * reproduces the message's own line breaks.
     */
    fun parse(body: String): Translation? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return null
        val sentences = root.getOrNull(0) as? JsonArray ?: return null
        val text = buildString {
            for (sentence in sentences) {
                val part = ((sentence as? JsonArray)?.getOrNull(0) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.contentOrNull ?: continue
                append(part)
            }
        }
        if (text.isEmpty()) return null
        val from = (root.getOrNull(2) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        return Translation(text, from)
    }

    /**
     * The code to ask for, given the phone's language.
     *
     * The language subtag is almost always what Google wants — it accepts both
     * the current and the legacy codes Java still hands out (`he`/`iw`,
     * `id`/`in`). Chinese is the exception: it is two written languages, and
     * which one a reader wants is in the script, or failing that the region.
     *
     * Only the leading letters are kept, because the result goes into a URL.
     */
    fun targetFor(language: String, script: String = "", country: String = ""): String {
        val lang = language.lowercase().takeWhile { it in 'a'..'z' }.ifEmpty { "en" }
        if (lang != "zh") return lang
        val traditional = script.equals("Hant", ignoreCase = true) ||
            (script.isEmpty() && country.uppercase() in setOf("TW", "HK", "MO"))
        return if (traditional) "zh-TW" else "zh-CN"
    }

    /**
     * What a reader can choose to translate into, as the codes Google takes.
     *
     * Google's own list, as its translator offers it. The current codes where
     * it accepts two (`he` not `iw`, `jv` not `jw`), because those are the
     * ones Android can put a name to. Chinese is the pair of written forms,
     * never bare `zh`.
     */
    val LANGUAGES: List<String> = listOf(
        "af", "ak", "am", "ar", "as", "ay", "az", "be", "bg", "bho", "bm", "bn", "bs",
        "ca", "ceb", "ckb", "co", "cs", "cy", "da", "de", "doi", "dv", "ee", "el", "en",
        "eo", "es", "et", "eu", "fa", "fi", "fil", "fr", "fy", "ga", "gd", "gl", "gn",
        "gom", "gu", "ha", "haw", "he", "hi", "hmn", "hr", "ht", "hu", "hy", "id", "ig",
        "ilo", "is", "it", "ja", "jv", "ka", "kk", "km", "kn", "ko", "kri", "ku", "ky",
        "la", "lb", "lg", "ln", "lo", "lt", "lus", "lv", "mai", "mg", "mi", "mk", "ml",
        "mn", "mni-Mtei", "mr", "ms", "mt", "my", "ne", "nl", "no", "nso", "ny", "om",
        "or", "pa", "pl", "ps", "pt", "qu", "ro", "ru", "rw", "sa", "sd", "si", "sk",
        "sl", "sm", "sn", "so", "sq", "sr", "st", "su", "sv", "sw", "ta", "te", "tg",
        "th", "ti", "tk", "tr", "ts", "tt", "ug", "uk", "ur", "uz", "vi", "xh", "yi",
        "yo", "zh-CN", "zh-TW", "zu",
    )

    /**
     * A stored choice, if it is still one of [LANGUAGES]; otherwise null,
     * which means the phone's language. So a value from an older build, or a
     * hand-edited file, falls back rather than going into a URL unchecked.
     */
    fun chosen(stored: String?): String? = stored?.takeIf { it in LANGUAGES }

    /**
     * Whether a message Google says is in [from] was already in [target], so
     * there was nothing to translate.
     *
     * A bare target matches any variant of itself — a reader asking for `pt`
     * does not need Brazilian turned into Portuguese — but `zh-TW` and `zh-CN`
     * are different things to be reading.
     */
    fun sameLanguage(from: String, target: String): Boolean =
        from.equals(target, ignoreCase = true) ||
            ('-' !in target && from.substringBefore('-').equals(target, ignoreCase = true))
}
