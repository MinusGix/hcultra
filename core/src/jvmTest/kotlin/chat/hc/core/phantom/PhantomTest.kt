package chat.hc.core.phantom

import chat.hc.core.session.Challenge
import chat.hc.core.session.Credentials
import chat.hc.core.session.ModAction
import chat.hc.core.session.Moderation
import chat.hc.core.session.SessionEvent
import chat.hc.core.session.SessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client against the real upstream server, with an admin we control.
 * Run with `./gradlew :core:phantomTest`; see probe/phantom/README.md.
 *
 * Real time throughout — runTest's virtual clock cannot drive a real socket.
 */
class PhantomTest {

    private fun phantom(block: suspend CoroutineScope.(sessions: MutableList<Recorded>) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val sessions = mutableListOf<Recorded>()
        try {
            withTimeout(60_000) { scope.block(sessions) }
        } finally {
            sessions.forEach { it.session.stop() }
            scope.cancel()
        }
    }

    private suspend fun waitUntil(timeoutMillis: Long = 5_000, label: String, pred: () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!pred()) delay(50) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $label", e)
        }
    }

    /** A forced flair reaches peers only via updateUser; chat frames still say `false`. */
    @Test
    fun forcedFlairShowsOnMessages() = phantom { sessions ->
        val ch = Phantom.channel("flair")
        val observer = Phantom.session(this, ch, Credentials(Phantom.nick("obs"))).also { sessions += it }
        val targetNick = Phantom.nick("tgt")
        val target = Phantom.session(this, ch, Credentials(targetNick)).also { sessions += it }
        observer.awaitLive()
        target.awaitLive()

        Phantom.admin(ch, "flair", "nick" to targetNick, "flair" to "🦊")
        waitUntil(label = "updateUser with the flair") {
            observer.session.roster.any { it.nick == targetNick && it.flair == "🦊" }
        }
        target.session.sendChat("hello")

        waitUntil(label = "the target's chat") {
            observer.seen<SessionEvent.Message>().any { it.frame.nick == targetNick }
        }
        val msg = observer.seen<SessionEvent.Message>().first { it.frame.nick == targetNick }
        assertEquals("🦊", msg.frame.flair)
    }

    /** `speak` must name its channel, or the server frisks silently and replies nothing. */
    @Test
    fun unmuzzleIsAcknowledged() = phantom { sessions ->
        val ch = Phantom.channel("muzzle")
        val mod = Phantom.session(this, ch, Credentials(Phantom.nick("mod"), Phantom.MOD_PASS)).also { sessions += it }
        val targetNick = Phantom.nick("tgt")
        val target = Phantom.session(this, ch, Credentials(targetNick)).also { sessions += it }
        mod.awaitLive()
        target.awaitLive()
        waitUntil(label = "target in the mod's roster") { mod.session.roster.any { it.nick == targetNick } }
        val user = mod.session.roster.first { it.nick == targetNick }

        mod.session.send(Moderation.frameFor(ModAction.Muzzle, ch, user)!!)
        waitUntil(label = "muzzle notice") { mod.seen<SessionEvent.Notice>().any { it.frame.id == 1203 } }
        mod.session.send(Moderation.frameFor(ModAction.Unmuzzle, ch, user)!!)
        waitUntil(label = "unmuzzle notice (Info.Mod.UNMUZZLED_DETAILED)") {
            mod.seen<SessionEvent.Notice>().any { it.frame.id == 1208 }
        }
    }

    /** A captcha channel asks, and the session waits on the user instead of failing. */
    @Test
    fun captchaChannelChallenges() = phantom { sessions ->
        val ch = Phantom.channel("captcha")
        Phantom.admin(ch, "captcha")
        val s = Phantom.session(this, ch, Credentials(Phantom.nick("cap"))).also { sessions += it }
        val state = s.awaitState(label = "Challenged") { it is SessionState.Challenged } as SessionState.Challenged
        assertTrue(state.challenge is Challenge.Captcha)
    }

    /** The password is asked for, answered, and the channel joined. */
    @Test
    fun passwordChannelAdmitsTheRightAnswer() = phantom { sessions ->
        val ch = Phantom.channel("pw")
        Phantom.admin(ch, "password", "password" to "hunter2")
        val s = Phantom.session(this, ch, Credentials(Phantom.nick("pw"))).also { sessions += it }
        s.awaitState(label = "password challenge") { (it as? SessionState.Challenged)?.challenge is Challenge.Password }
        s.session.answer("hunter2")
        s.awaitLive()
    }

    /**
     * The phone drops, but the server still holds its socket. Token restore is
     * refused into a password channel, and the cold join collides with that
     * ghost — which must be waited out, not treated as a taken nick.
     */
    @Test
    fun reconnectWaitsOutItsOwnGhost() = phantom { sessions ->
        val ch = Phantom.channel("ghost")
        Phantom.admin(ch, "password", "password" to "hunter2")
        val s = Phantom.session(this, ch, Credentials(Phantom.nick("gh"))).also { sessions += it }
        s.awaitState(label = "password challenge") { it is SessionState.Challenged }
        s.session.answer("hunter2")
        s.awaitLive()

        Phantom.freeze(1)
        s.awaitState(label = "Reconnecting") { it is SessionState.Reconnecting }
        // Long enough for several rejoins to hit the ghost.
        delay(2_000)
        val nameTaken = s.seen<SessionEvent.Disconnected>().count { it.cause?.contains("previous connection") == true }
        assertTrue(nameTaken >= 1, "expected rejoins to collide with the ghost; saw ${s.snapshot(s.states)}")
        assertTrue(s.session.state.value !is SessionState.Failed, "gave up on its own ghost")

        Phantom.release()
        // Each refused rejoin spent a join and a password answer, so by now our
        // own RateGovernor is pacing the next attempt by several seconds — as
        // it should, or the real server would start frisking.
        s.awaitLive(timeoutMillis = 20_000)
    }

    /**
     * Turned away by a lock, the server re-joins you to ?purgatory under a
     * random nick and sends *that* onlineSet, with no warn. It is a refusal.
     */
    @Test
    fun lockedChannelIsNotMistakenForPurgatory() = phantom { sessions ->
        val ch = Phantom.channel("locked")
        Phantom.admin(ch, "lock", "level" to "channelOwner")
        val s = Phantom.session(this, ch, Credentials(Phantom.nick("lk"))).also { sessions += it }
        val state = s.awaitState(label = "settled") { it is SessionState.Live || it is SessionState.Failed }
        assertTrue(state is SessionState.Failed, "admitted to purgatory as ?$ch")
    }

    /** A lock below our level is no lock to us: a channel owner's lock lets a global mod in. */
    @Test
    fun lockBelowOurLevelAdmits() = phantom { sessions ->
        val ch = Phantom.channel("locked")
        Phantom.admin(ch, "lock", "level" to "channelOwner")
        val s = Phantom.session(this, ch, Credentials(Phantom.nick("md"), Phantom.MOD_PASS)).also { sessions += it }
        s.awaitLive()
    }
}
