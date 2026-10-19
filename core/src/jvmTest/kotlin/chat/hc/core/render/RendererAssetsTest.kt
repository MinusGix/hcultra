package chat.hc.core.render

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The renderer's stylesheet lives in the Android app's assets, and a layout
 * whose class it never mentions would switch to and look exactly like Inline.
 * Read from the source tree: the JVM tests run from `core/`.
 */
class RendererAssetsTest {

    private val css = File("../app-android/src/main/assets/renderer/app.css")

    @Test
    fun everyLayoutIsStyled() {
        assertTrue(css.isFile, "no stylesheet at ${css.absolutePath}")
        val text = css.readText()
        // Inline is the page's unstyled default; the others must be targeted.
        NickLayout.entries.filter { it != NickLayout.Inline }.forEach { layout ->
            assertTrue(Regex("""\.${layout.cssClass}\b""").containsMatchIn(text), "${layout.cssClass} is not in app.css")
        }
    }

    /** Same failure, for fonts: a key with no rule is a choice that changes nothing. */
    @Test
    fun everyFontIsStyled() {
        val text = css.readText()
        TranscriptFont.entries.filter { it != TranscriptFont.System }.forEach { font ->
            assertTrue("""html[data-font="${font.key}"]""" in text, "${font.key} is not in app.css")
        }
    }
}
