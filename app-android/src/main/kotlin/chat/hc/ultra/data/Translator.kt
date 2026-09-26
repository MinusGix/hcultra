package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.translate.GoogleTranslate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** What a Translate tap came back with, as the page will show it. */
sealed interface TranslateResult {
    /** [from] is the source language's name, in the reader's own language. */
    data class Done(val text: String, val from: String) : TranslateResult

    /** Nothing to do: the message is in [language] already. */
    data class Already(val language: String) : TranslateResult

    /** A reason worth showing, as with [UpdateStatus.Failed]. */
    data class Failed(val reason: String) : TranslateResult

    /** Nothing was sent — translation is switched off; the page only needs to put its button back. */
    data object Cancelled : TranslateResult
}

/**
 * Whether messages get a Translate button at all.
 *
 * Off by default: most people read the channels they join in the language
 * they are in, and a button they will never use is one more thing to hit by
 * mistake next to Copy. Turning it on is also the agreement to messages going
 * to Google — the setting says so — which is why there is no separate
 * question on the first tap, as there is none for images.
 */
class TranslatePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hc_translate", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /**
     * The language to translate into, as one of [GoogleTranslate.LANGUAGES],
     * or null for the phone's own. A reader on an English phone may still
     * follow a hard sentence better in the language they grew up with.
     */
    var target: String?
        get() = GoogleTranslate.chosen(prefs.getString(KEY_TARGET, null))
        set(value) = prefs.edit().apply {
            val code = GoogleTranslate.chosen(value)
            if (code == null) remove(KEY_TARGET) else putString(KEY_TARGET, code)
        }.apply()

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_TARGET = "target"
    }
}

/**
 * Translate one message into the phone's language, through Google's keyless
 * endpoint ([GoogleTranslate]).
 *
 * Only the text goes: no nick, no channel, nothing identifying the app. It is
 * still a message a stranger wrote, handed to Google because the reader asked,
 * which is what turning on [TranslatePrefs.enabled] agrees to.
 *
 * Answers are remembered for the life of the process, so hiding a translation
 * and showing it again, or two people pasting the same line, is not a second
 * request.
 */
object Translator {

    private val cache = object : LinkedHashMap<String, TranslateResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, TranslateResult>?) =
            size > 100
    }

    /** What the phone's own language is, as a code to translate into. */
    fun phoneTarget(): String {
        val locale = Locale.getDefault()
        return GoogleTranslate.targetFor(locale.language, locale.script, locale.country)
    }

    /**
     * A language's name, in [inLocale] — its own, by default, so a reader
     * finds "Español" whatever the phone is set to. Chinese is named by its
     * written form, which is what the choice between the two actually is.
     * The code itself if Android has no name for it.
     */
    fun languageName(code: String, inLocale: Locale? = null): String {
        val tag = when (code) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> code
        }
        val locale = Locale.forLanguageTag(tag)
        val name = locale.getDisplayName(inLocale ?: locale)
        return name.ifBlank { code }.replaceFirstChar { it.titlecase(inLocale ?: locale) }
    }

    /** Into [target], one of [GoogleTranslate.LANGUAGES] or [phoneTarget]. */
    suspend fun translate(text: String, target: String): TranslateResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext TranslateResult.Failed("Nothing to translate")
        if (text.length > GoogleTranslate.MAX_CHARS) {
            return@withContext TranslateResult.Failed("Too long to translate")
        }
        val key = "$target\u0000$text"
        synchronized(cache) { cache[key] }?.let { return@withContext it }

        val body = runCatching { fetch(GoogleTranslate.url(target), text) }
            .getOrElse { return@withContext TranslateResult.Failed("Could not reach Google") }
            ?: return@withContext TranslateResult.Failed("Google refused — try again later")
        val translation = GoogleTranslate.parse(body)
            ?: return@withContext TranslateResult.Failed("Google's answer was not a translation")

        // In the phone's language, like the rest of the app, whatever the
        // translation is into.
        val from = languageName(translation.from, Locale.getDefault())
        val result = if (GoogleTranslate.sameLanguage(translation.from, target)) {
            TranslateResult.Already(from)
        } else {
            TranslateResult.Done(translation.text, from)
        }
        synchronized(cache) { cache[key] = result }
        result
    }

    /** The response body, or null if the server answered with anything but 200. */
    private fun fetch(url: String, text: String): String? {
        val form = "q=" + URLEncoder.encode(text, "UTF-8")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            // Not Android's default. Google sends anything calling itself
            // `Dalvik/…` to its bot check, for every language, while it answers
            // other agents — curl, a browser, a bare app name — normally.
            setRequestProperty("User-Agent", "hcultra")
            // The only redirect this endpoint gives is to that check, a page
            // for a person to solve; following it would only fetch the page.
            instanceFollowRedirects = false
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return try {
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
