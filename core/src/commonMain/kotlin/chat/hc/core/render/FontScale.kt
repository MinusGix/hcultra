package chat.hc.core.render

/**
 * How large the transcript's text is, as a multiplier on the renderer's base
 * size.
 *
 * A fixed ladder rather than a free-floating number, for two reasons. A pair of
 * up/down buttons has to move by *something*, and a ladder makes that step a
 * decision taken once here instead of an increment scattered across the UI. And
 * it bounds the setting at both ends: the transcript is rendered by a WebView
 * over a page whose paddings and gutters are sized in `em`, so it scales
 * honestly, but nothing scales honestly forever. The largest rung is roughly
 * double, which is legible without leaving the composer no room; the smallest
 * stops well short of the size at which the text stops being text.
 *
 * Sitting in core rather than the Android module because the renderer assets
 * are shared: an iOS host would step the same ladder over the same page.
 */
object FontScale {

    /** Ascending, and the value the app ships with is [DEFAULT]. */
    val STEPS: List<Float> = listOf(0.8f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f)

    const val DEFAULT: Float = 1.0f

    val MIN: Float get() = STEPS.first()
    val MAX: Float get() = STEPS.last()

    /**
     * The nearest rung to an arbitrary value.
     *
     * Everything that reads a stored scale goes through this, so a preferences
     * file written by an older build — or edited by hand, or holding a value
     * from a ladder this one no longer has — can only ever produce a size the
     * buttons can also reach. A scale you cannot step away from is the one way
     * this setting could strand somebody at an unreadable size.
     */
    fun snap(value: Float): Float {
        if (value.isNaN()) return DEFAULT
        return STEPS.minBy { step -> kotlin.math.abs(step - value) }
    }

    /** The next rung up, or the same value when already at the top. */
    fun larger(value: Float): Float = step(value, +1)

    /** The next rung down, or the same value when already at the bottom. */
    fun smaller(value: Float): Float = step(value, -1)

    fun canGrow(value: Float): Boolean = larger(value) != snap(value)

    fun canShrink(value: Float): Boolean = smaller(value) != snap(value)

    /** "100%", "115%" — what the buttons are moving, in the only unit it has. */
    fun percentLabel(value: Float): String =
        "${(snap(value) * 100).let { kotlin.math.round(it) }.toInt()}%"

    private fun step(value: Float, by: Int): Float {
        val index = STEPS.indexOf(snap(value))
        return STEPS[(index + by).coerceIn(0, STEPS.lastIndex)]
    }
}
