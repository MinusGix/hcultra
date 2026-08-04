package chat.hc.core.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.pow

/**
 * Client-side mirror of the server's RateLimiter
 * (hackchat-server/src/serverLib/RateLimiter.js).
 *
 * The server scores by **remote address**, not by socket, so every channel
 * session we hold shares one budget. This must therefore be a single instance
 * shared across all sessions — one governor per device, not per connection.
 *
 * Being limited is not a soft failure: the server stops responding to commands
 * and, past enough weight, can `arrest` the address outright. So we stall
 * locally rather than discover the limit remotely.
 */
class RateGovernor(
    private val halflifeMillis: Long = 30_000,
    /**
     * Server threshold is 25. We hold well under it because we do not model
     * every command's weight, and other clients on the same NAT share the score.
     */
    private val ceiling: Double = 12.0,
    private val now: () -> Long = { currentTimeMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    private val mutex = Mutex()
    private var score = 0.0
    private var stamp = now()

    /** Decayed score as of [at]. */
    fun projected(at: Long = now()): Double =
        score * 2.0.pow(-(at - stamp).toDouble() / halflifeMillis)

    /** Milliseconds to wait before [cost] can be spent without crossing the ceiling. */
    fun waitMillis(cost: Double, at: Long = now()): Long {
        val headroom = ceiling - cost
        require(headroom > 0) { "cost $cost exceeds ceiling $ceiling" }
        val current = projected(at)
        if (current <= headroom) return 0
        return ceil(halflifeMillis * log2(current / headroom)).toLong()
    }

    /** Suspends until [cost] is affordable, then records it. */
    suspend fun spend(cost: Double) {
        if (cost <= 0.0) return
        mutex.withLock {
            val wait = waitMillis(cost)
            if (wait > 0) sleep(wait)
            val t = now()
            score = projected(t) + cost
            stamp = t
        }
    }

    /**
     * Record weight the server charged us without our asking — e.g. when we
     * receive a RATELIMIT warn, meaning our model was optimistic.
     */
    suspend fun penalize(cost: Double) = mutex.withLock {
        val t = now()
        score = projected(t) + cost
        stamp = t
    }

    /** Command weights, from the server command modules. */
    object Cost {
        const val JOIN = 3.0
        const val HELP = 2.0
        const val INVITE = 2.0

        /**
         * `session` is currently free due to a bug in session.js (it calls
         * frisk with an address string and no delta, poisoning a junk record
         * with NaN). Budget 1.0 anyway: the fix is trivial and we should not
         * build a reconnect policy that depends on the bug.
         * @see docs/upstream-asks.md
         */
        const val SESSION = 1.0

        /** parseText failure or an oversized customId. Two of these hard-limits us. */
        const val MALFORMED = 13.0

        const val DEFAULT = 1.0

        /** chat.js: `text.length / 83 / 4` */
        fun chat(text: String): Double = text.length / 83.0 / 4.0
    }
}

internal expect fun currentTimeMillis(): Long
