package chat.hc.core.session

/**
 * How long a channel was gone, said the way a person would say it.
 *
 * The two largest units that are not zero, and no more: "2 hours, 5 minutes"
 * answers "how much did I miss" and the seconds on top of it would not. Under
 * a minute the seconds are the whole answer, so they stay.
 */
object Downtime {

    fun describe(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1000
        if (seconds == 0L) return "less than a second"
        val parts = listOf(
            seconds / DAY to "day",
            seconds % DAY / HOUR to "hour",
            seconds % HOUR / MINUTE to "minute",
            seconds % MINUTE to "second",
        )
        return parts
            .dropWhile { it.first == 0L }
            .take(2)
            .filter { it.first != 0L }
            .joinToString(", ") { (n, unit) -> "$n $unit" + if (n == 1L) "" else "s" }
    }

    /** The transcript line for a channel back after [millis] away. */
    fun notice(millis: Long): String = "Reconnected. You were disconnected for ${describe(millis)}."

    private const val MINUTE = 60L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR
}
