package chat.hc.core.session

import chat.hc.core.protocol.Inbound
import chat.hc.core.protocol.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The roster, and in particular the order it is in.
 *
 * Order is not incidental here: the user list shows the roster exactly as the
 * server gave it, so anything that quietly moved a person within it would move
 * them on screen for no reason the reader could see.
 */
class PresenceTrackerTest {

    private fun user(nick: String, id: Long, level: Int = 100, isme: Boolean = false) =
        User(nick = nick, userid = id, level = level, isme = isme)

    private fun PresenceTracker.nicks() = roster.map { it.nick }

    @Test
    fun onlineSetOrderIsKept() {
        val presence = PresenceTracker()
        presence.reset(listOf(user("zoe", 1), user("adam", 2), user("mia", 3)))

        assertEquals(listOf("zoe", "adam", "mia"), presence.nicks())
    }

    @Test
    fun arrivalsGoOnTheEnd() {
        val presence = PresenceTracker()
        presence.reset(listOf(user("zoe", 1), user("adam", 2)))
        presence.add(user("mia", 3))

        assertEquals(listOf("zoe", "adam", "mia"), presence.nicks())
    }

    /** A nick change or a privilege change must not move someone down the list. */
    @Test
    fun anUpdateKeepsItsPlace() {
        val presence = PresenceTracker()
        presence.reset(listOf(user("zoe", 1), user("adam", 2), user("mia", 3)))
        presence.update(Inbound.UpdateUser(nick = "adamant", userid = 2, level = 999999))

        assertEquals(listOf("zoe", "adamant", "mia"), presence.nicks())
        assertEquals(999999, presence[2]?.level)
    }

    /** `onlineAdd` for someone already present is a re-announcement, not a move. */
    @Test
    fun readdingSomeoneKeepsTheirPlace() {
        val presence = PresenceTracker()
        presence.reset(listOf(user("zoe", 1), user("adam", 2), user("mia", 3)))
        presence.add(user("adam", 2, level = 9999))

        assertEquals(listOf("zoe", "adam", "mia"), presence.nicks())
    }

    @Test
    fun departureLeavesTheRestWhereTheyWere() {
        val presence = PresenceTracker()
        presence.reset(listOf(user("zoe", 1), user("adam", 2), user("mia", 3)))
        presence.remove(2)

        assertEquals(listOf("zoe", "mia"), presence.nicks())
    }

    /**
     * The handoff case the tracker is keyed by userid for: mid make-before-break,
     * `onlineSet` lists us on both sockets under one userid. Keyed by nick this
     * would leave a phantom, and the count above the list would read one high.
     */
    @Test
    fun ourOwnDoubledEntryCollapses() {
        val presence = PresenceTracker()
        presence.reset(
            listOf(
                user("me", 7, isme = true),
                user("peer", 8),
                user("me", 7, isme = true),
            )
        )

        assertEquals(listOf("me", "peer"), presence.nicks())
        assertEquals(2, presence.size())
        assertTrue(presence[7]?.isme == true)
    }

    /** A partial record must not blank what a fuller one already established. */
    @Test
    fun mergingKeepsWhatTheNewRecordOmits() {
        val presence = PresenceTracker()
        presence.reset(listOf(User(nick = "adam", userid = 2, trip = "aBc12", color = "d73737")))
        presence.add(User(nick = "adam", userid = 2))

        assertEquals("aBc12", presence[2]?.trip)
        assertEquals("d73737", presence[2]?.color)
    }
}
