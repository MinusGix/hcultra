package chat.hc.core.translate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleTranslateTest {

    @Test
    fun sentencesAreJoinedAndTheSourceRead() {
        // A real response, trimmed of the model metadata Google appends.
        val body = """[[["Hello friends, how are you?\n","Hola amigos, ¿cómo están?\n",null,null,3],""" +
            """["All good `code` here.","Todo bien `code` aquí.",null,null,3]],null,"es",null,null,null,1,[]]"""
        assertEquals(
            Translation("Hello friends, how are you?\nAll good `code` here.", "es"),
            GoogleTranslate.parse(body),
        )
    }

    @Test
    fun anythingElseIsNoAnswer() {
        assertNull(GoogleTranslate.parse(""))
        assertNull(GoogleTranslate.parse("<html>429 Too Many Requests</html>"))
        assertNull(GoogleTranslate.parse("{}"))
        assertNull(GoogleTranslate.parse("[]"))
        assertNull(GoogleTranslate.parse("""[[],null,"es"]"""), "no sentences")
        assertNull(GoogleTranslate.parse("""[[["Hi","Hola"]],null,null]"""), "no detected language")
    }

    @Test
    fun oddSentencesAreSkippedRatherThanFatal() {
        val body = """[[["Hi. ","Hola. "],null,[null],["there","ahí"]],null,"es"]"""
        assertEquals(Translation("Hi. there", "es"), GoogleTranslate.parse(body))
    }

    @Test
    fun theTargetIsTheLanguage() {
        assertEquals("de", GoogleTranslate.targetFor("de", country = "AT"))
        assertEquals("pt", GoogleTranslate.targetFor("pt", country = "BR"))
        assertEquals("iw", GoogleTranslate.targetFor("iw"), "Google takes the legacy code")
    }

    @Test
    fun chineseIsDecidedByScriptThenRegion() {
        assertEquals("zh-CN", GoogleTranslate.targetFor("zh"))
        assertEquals("zh-CN", GoogleTranslate.targetFor("zh", country = "CN"))
        assertEquals("zh-TW", GoogleTranslate.targetFor("zh", country = "TW"))
        assertEquals("zh-TW", GoogleTranslate.targetFor("zh", country = "HK"))
        assertEquals("zh-TW", GoogleTranslate.targetFor("zh", script = "Hant", country = "US"))
        assertEquals("zh-CN", GoogleTranslate.targetFor("zh", script = "Hans", country = "HK"))
    }

    @Test
    fun theTargetIsSafeInAUrl() {
        assertEquals("en", GoogleTranslate.targetFor("en&tl=xx"))
        assertEquals("en", GoogleTranslate.targetFor(""))
    }

    @Test
    fun onlyAListedLanguageIsAChoice() {
        assertEquals("es", GoogleTranslate.chosen("es"))
        assertEquals("zh-TW", GoogleTranslate.chosen("zh-TW"))
        assertNull(GoogleTranslate.chosen(null), "unset is the phone's language")
        assertNull(GoogleTranslate.chosen("zh"), "Chinese is chosen by written form")
        assertNull(GoogleTranslate.chosen("en&tl=xx"))
    }

    @Test
    fun everyListedLanguageIsAPlainCode() {
        val shape = Regex("[a-z]{2,3}(-[A-Za-z]{2,4})?")
        GoogleTranslate.LANGUAGES.forEach { assertTrue(shape.matches(it), it) }
        assertEquals(GoogleTranslate.LANGUAGES.size, GoogleTranslate.LANGUAGES.toSet().size, "no duplicates")
    }

    @Test
    fun aMessageAlreadyInTheTargetNeedsNothing() {
        assertTrue(GoogleTranslate.sameLanguage("en", "en"))
        assertTrue(GoogleTranslate.sameLanguage("pt-PT", "pt"))
        assertFalse(GoogleTranslate.sameLanguage("zh-CN", "zh-TW"))
        assertFalse(GoogleTranslate.sameLanguage("es", "en"))
    }
}
