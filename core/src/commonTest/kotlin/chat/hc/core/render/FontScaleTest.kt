package chat.hc.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FontScaleTest {

    @Test
    fun stepsUpAndDownTheLadder() {
        assertEquals(1.15f, FontScale.larger(1.0f))
        assertEquals(0.9f, FontScale.smaller(1.0f))
    }

    /** Both ends are walls, not wrap-arounds. */
    @Test
    fun stopsAtBothEnds() {
        assertEquals(FontScale.MAX, FontScale.larger(FontScale.MAX))
        assertEquals(FontScale.MIN, FontScale.smaller(FontScale.MIN))
        assertFalse(FontScale.canGrow(FontScale.MAX))
        assertFalse(FontScale.canShrink(FontScale.MIN))
        assertTrue(FontScale.canGrow(FontScale.MIN))
        assertTrue(FontScale.canShrink(FontScale.MAX))
    }

    /**
     * The one that matters: a stored value from anywhere — an older ladder, a
     * hand-edited preferences file, a truncated float — has to land on a rung
     * the buttons can move away from. A scale that could not be stepped is the
     * only way this setting strands somebody at a size they cannot read.
     */
    @Test
    fun snapsAnythingOntoTheLadder() {
        assertEquals(1.15f, FontScale.snap(1.14999f))
        assertEquals(FontScale.MAX, FontScale.snap(99f))
        assertEquals(FontScale.MIN, FontScale.snap(0.01f))
        assertEquals(FontScale.MIN, FontScale.snap(-3f))
        assertEquals(FontScale.DEFAULT, FontScale.snap(Float.NaN))
        FontScale.STEPS.forEach { assertEquals(it, FontScale.snap(it)) }
    }

    @Test
    fun everySnappedValueCanBeSteppedBackToTheDefault() {
        listOf(0.01f, 0.83f, 1.0f, 1.4f, 12f).forEach { start ->
            var value = FontScale.snap(start)
            repeat(FontScale.STEPS.size) {
                value = if (value < FontScale.DEFAULT) FontScale.larger(value)
                else if (value > FontScale.DEFAULT) FontScale.smaller(value)
                else value
            }
            assertEquals(FontScale.DEFAULT, value)
        }
    }

    @Test
    fun labelsAsWholePercentages() {
        assertEquals("100%", FontScale.percentLabel(1.0f))
        assertEquals("115%", FontScale.percentLabel(1.15f))
        assertEquals("200%", FontScale.percentLabel(2.0f))
    }

    /** What reaches the page is a plain JS number literal, and never `NaN`. */
    @Test
    fun bridgeCallIsAJsNumber() {
        assertEquals("HC.setFontScale(1.15);", RendererBridge.fontScaleCall(1.15f))
        assertEquals("HC.setFontScale(2.0);", RendererBridge.fontScaleCall(50f))
        assertEquals("HC.setFontScale(1.0);", RendererBridge.fontScaleCall(Float.NaN))
    }
}
