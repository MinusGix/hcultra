package chat.hc.core.render

/**
 * Which typeface the transcript is set in.
 *
 * A short list of generic families rather than a free-text font name. The page
 * can only draw what the phone has installed, and the one thing every Android
 * phone is sure to have is a font behind each of these. A name typed into a
 * field would work on the phone it was typed on and quietly fall back on the
 * next, with nothing to say it had.
 *
 * Each is a value of `data-font` on the page's root element, which app.css
 * maps to a font stack. Keeping the stacks in the stylesheet keeps the page's
 * typography in one file, and means an iOS host picks the same faces by
 * sending the same key.
 */
enum class TranscriptFont(val key: String, val label: String) {
    /**
     * The stylesheet's own sans-serif, or the scheme's font where the scheme
     * has one. The page draws it with no `data-font` rule at all.
     */
    System("system", "Default"),

    Serif("serif", "Serif"),

    /** For channels that are mostly code, or anyone who reads better with every letter the same width. */
    Monospace("mono", "Monospace"),

    /** Roboto Condensed on stock Android: more words on a line at the same size. */
    Condensed("condensed", "Condensed"),
    ;

    companion object {
        val DEFAULT = System

        /** Stored by name; a name this build does not know is the default, not a crash. */
        fun from(name: String?): TranscriptFont =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
