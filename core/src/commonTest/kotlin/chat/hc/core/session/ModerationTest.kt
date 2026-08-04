package chat.hc.core.session

import chat.hc.core.protocol.FrameCodec
import chat.hc.core.protocol.Levels
import chat.hc.core.protocol.Outbound
import chat.hc.core.protocol.User
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The gates differ per command and a rejected attempt costs 10 rate-limit
 * points of 25 with no reply, so an over-permissive UI would throttle the user
 * after two taps. These pin the thresholds to the server's own.
 */
class ModerationTest {

    private fun user(
        userid: Long,
        level: Int = Levels.DEFAULT,
        hash: String? = "abc123",
        isme: Boolean = false,
    ) = User(nick = "u$userid", userid = userid, level = level, hash = hash, isme = isme)

    @Test
    fun thresholdsMatchTheServer() {
        // kick: isChannelModerator; ban/dumb/speak: isModerator
        assertEquals(Levels.CHANNEL_MODERATOR, ModAction.Kick.minimumLevel)
        assertEquals(Levels.MODERATOR, ModAction.Ban.minimumLevel)
        assertEquals(Levels.MODERATOR, ModAction.Muzzle.minimumLevel)
        assertEquals(Levels.MODERATOR, ModAction.Unmuzzle.minimumLevel)
    }

    @Test
    fun ordinaryUsersGetNothing() {
        val me = user(1, Levels.DEFAULT, isme = true)
        assertTrue(Moderation.available(me, user(2)).isEmpty())
    }

    /** A channel moderator can kick but must not be offered ban. */
    @Test
    fun channelModeratorCanOnlyKick() {
        val me = user(1, Levels.CHANNEL_MODERATOR, isme = true)
        assertEquals(listOf(ModAction.Kick), Moderation.available(me, user(2)))
    }

    @Test
    fun globalModeratorGetsEverything() {
        val me = user(1, Levels.MODERATOR, isme = true)
        val actions = Moderation.available(me, user(2))
        assertTrue(actions.containsAll(ModAction.entries.toList()))
    }

    /** Kicking yourself is legal server-side and never intended. */
    @Test
    fun noActionsAgainstYourself() {
        val me = user(1, Levels.MODERATOR, isme = true)
        assertTrue(Moderation.available(me, me).isEmpty())
    }

    /** `speak` keys on hash; without one the action cannot be built. */
    @Test
    fun unmuzzleNeedsAHash() {
        val me = user(1, Levels.MODERATOR, isme = true)
        val hashless = user(2, hash = null)
        assertTrue(ModAction.Unmuzzle !in Moderation.available(me, hashless))
        assertNull(Moderation.frameFor(ModAction.Unmuzzle, "room", hashless))
    }

    @Test
    fun unknownSelfGetsNothing() {
        assertTrue(Moderation.available(null, user(2)).isEmpty())
    }

    // ---- wire format ----

    private fun encoded(frame: Outbound) =
        Json.parseToJsonElement(FrameCodec.encode(frame)).jsonObject

    /** v2 targets by numeric userid and needs an explicit channel. */
    @Test
    fun kickAndBanCarryUseridAndChannel() {
        val kick = encoded(Moderation.frameFor(ModAction.Kick, "room", user(42))!!)
        assertEquals("kick", kick["cmd"]?.jsonPrimitive?.content)
        assertEquals("room", kick["channel"]?.jsonPrimitive?.content)
        assertEquals(42L, kick["userid"]?.jsonPrimitive?.content?.toLong())

        val ban = encoded(Moderation.frameFor(ModAction.Ban, "room", user(42))!!)
        assertEquals("ban", ban["cmd"]?.jsonPrimitive?.content)
        assertEquals(42L, ban["userid"]?.jsonPrimitive?.content?.toLong())
    }

    /** The muzzle command is named `dumb` on the wire. */
    @Test
    fun muzzleUsesTheServerCommandName() {
        val f = encoded(Moderation.frameFor(ModAction.Muzzle, "room", user(42))!!)
        assertEquals("dumb", f["cmd"]?.jsonPrimitive?.content)
    }

    /** `speak` takes a hash and no channel — the ban outlives their presence. */
    @Test
    fun unmuzzleUsesHashNotUserid() {
        val f = encoded(Moderation.frameFor(ModAction.Unmuzzle, "room", user(42, hash = "deadbeef"))!!)
        assertEquals("speak", f["cmd"]?.jsonPrimitive?.content)
        assertEquals("deadbeef", f["hash"]?.jsonPrimitive?.content)
        assertTrue("userid" !in f)
    }
}
