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
        g.spend(7.0) // + the frame point
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
            now = clock::now,
            sleep = { slept += it; clock.t += it },
        )
        // A join frame weighs 3 + 1. Six of them reach 24; a seventh would make
        // 28, and even landing exactly on 25 is refused, so it must wait.
        repeat(6) { g.spend(RateGovernor.Cost.JOIN) }
        assertEquals(0L, slept, "six joins should fit without stalling")
        g.spend(RateGovernor.Cost.JOIN)
        assertTrue(slept > 0, "expected the governor to stall before the 7th join")
        assertTrue(g.projected() < 25.0, "threshold reached: ${g.projected()}")
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
            now = clock::now,
            sleep = { clock.t += it },
        )
        repeat(10) { g.spend(RateGovernor.Cost.JOIN) }
        assertTrue(g.projected() < 25.0, "ten joins reached the threshold: ${g.projected()}")
    }

    /**
     * The server charges every frame a point before its command runs, so a
     * short chat costs about 1, not about 0.01. Twenty-five of them in a burst
     * reach the threshold; the governor must hold the twenty-fifth.
     */
    @Test
    fun shortChatsCostAFrameEach() = runTest {
        val clock = Clock()
        var slept = 0L
        val g = RateGovernor(now = clock::now, sleep = { slept += it; clock.t += it })
        val hi = RateGovernor.Cost.chat("hi")
        repeat(24) { g.spend(hi) }
        assertEquals(0L, slept, "24 short chats should fit")
        assertTrue(g.projected() > 24.0)
        g.spend(hi)
        assertTrue(slept > 0, "the 25th short chat would have reached the threshold")
        assertTrue(g.projected() < 25.0, "threshold reached: ${g.projected()}")
    }

    /** Landing exactly on the threshold is refused server-side, so it must wait too. */
    @Test
    fun exactlyAtTheThresholdWaits() = runTest {
        val clock = Clock()
        var slept = 0L
        val g = RateGovernor(now = clock::now, sleep = { slept += it; clock.t += it })
        g.spend(20.0) // score 21
        g.spend(3.0) // 21 + 4 == 25: refused if sent now
        assertTrue(slept > 0, "sent a frame that lands exactly on 25")
        assertTrue(g.projected() < 25.0)
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
