package chat.hc.core.net

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateGovernorTest {

    private class Clock(var t: Long = 0) {
        fun now() = t
    }

    @Test
    fun scoreDecaysByHalflife() = runTest {
        val clock = Clock()
        val g = RateGovernor(halflifeMillis = 30_000, now = clock::now, sleep = {})
        g.spend(8.0)
        assertEquals(8.0, g.projected(), 0.001)
        clock.t += 30_000
        assertEquals(4.0, g.projected(), 0.001)
        clock.t += 30_000
        assertEquals(2.0, g.projected(), 0.001)
    }

    @Test
    fun stallsUntilAffordable() = runTest {
        val clock = Clock()
        var slept = 0L
        val g = RateGovernor(
            halflifeMillis = 30_000,
            ceiling = 12.0,
            now = clock::now,
            sleep = { slept += it; clock.t += it },
        )
        // Headroom for a cost-3 join is 12-3 = 9, so four joins (score 12) fit
        // exactly and the fifth is the first that must wait.
        repeat(4) { g.spend(RateGovernor.Cost.JOIN) }
        assertEquals(0L, slept, "four joins should fit without stalling")
        g.spend(RateGovernor.Cost.JOIN)
        assertTrue(slept > 0, "expected the governor to stall before the 5th join")
        assertTrue(g.projected() <= 12.0, "ceiling breached: ${g.projected()}")
    }

    /**
     * The server scores per remote address, so one governor is shared by every
     * channel session. Concurrent joins across channels must still stay bounded.
     */
    @Test
    fun sharedBudgetAcrossSessionsStaysUnderCeiling() = runTest {
        val clock = Clock()
        val g = RateGovernor(
            halflifeMillis = 30_000,
            ceiling = 12.0,
            now = clock::now,
            sleep = { clock.t += it },
        )
        repeat(10) { g.spend(RateGovernor.Cost.JOIN) }
        assertTrue(g.projected() <= 12.0, "ten joins breached the ceiling: ${g.projected()}")
    }

    @Test
    fun chatCostMatchesServerFormula() {
        val text = "x".repeat(332) // 332 / 83 / 4 == 1.0
        assertEquals(1.0, RateGovernor.Cost.chat(text), 0.0001)
    }

    /** Two malformed frames inside one halflife is a hard limit on the server. */
    @Test
    fun malformedCostIsRecognisedAsDangerous() {
        assertTrue(RateGovernor.Cost.MALFORMED * 2 > 25.0)
    }
}
