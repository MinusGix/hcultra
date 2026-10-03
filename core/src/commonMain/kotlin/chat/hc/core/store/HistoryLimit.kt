package chat.hc.core.store

/**
 * How much of a channel's conversation to keep, by count and optionally by age.
 * Whichever is reached first trims.
 *
 * History lives only in memory and the server cannot re-serve any of it, so
 * both limits are about how much the user wants kept, not about what can be
 * fetched again. The cost of keeping a lot is memory in the service, which is
 * small; the renderer draws only a window of it, so a big history does not
 * slow the transcript down.
 */
data class HistoryLimit(
    val maxLines: Int = DEFAULT_LINES,
    /** Null keeps messages however old they are, up to [maxLines]. */
    val maxAgeMillis: Long? = null,
) {
    init {
        require(maxLines >= 1) { "maxLines must be at least 1, was $maxLines" }
        require(maxAgeMillis == null || maxAgeMillis > 0) { "maxAgeMillis must be positive, was $maxAgeMillis" }
    }

    companion object {
        const val DEFAULT_LINES = 10_000
        const val DAY_MILLIS = 24L * 60 * 60 * 1000
    }
}
