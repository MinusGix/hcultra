package chat.hc.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The server sends an identical whisper frame to sender and recipient — verified
 * on live with two sockets — so getting direction wrong would label our own
 * outgoing whisper as if the other person had sent it. Worth pinning down.
 */
class WhisperResolverTest {

    private val me = 111L
    private val them = 222L
    private val roster = mapOf(me to "myself", them to "peer")
    private val lookup: (Long) -> String? = { roster[it] }

    @Test
    fun incomingIsAttributedToTheSender() {
        val r = WhisperResolver.resolve(from = them, to = me, myUserid = me, lookupNick = lookup)
        assertFalse(r.outgoing)
        assertEquals(them, r.otherId)
        assertEquals("peer", r.nick)
    }

    @Test
    fun outgoingIsAttributedToTheRecipient() {
        val r = WhisperResolver.resolve(from = me, to = them, myUserid = me, lookupNick = lookup)
        assertTrue(r.outgoing)
        assertEquals(them, r.otherId)
        assertEquals("peer", r.nick)
    }

    /**
     * Before the handshake completes we may not know our own userid. Guessing
     * "outgoing" would silently mislabel someone else's private message as ours.
     */
    @Test
    fun unknownSelfIsTreatedAsIncoming() {
        val r = WhisperResolver.resolve(from = them, to = me, myUserid = null, lookupNick = lookup)
        assertFalse(r.outgoing)
        assertEquals(them, r.otherId)
    }

    /** The sender can leave before we render; the label must still say something. */
    @Test
    fun missingNickFallsBackToUserid() {
        val r = WhisperResolver.resolve(from = 999L, to = me, myUserid = me, lookupNick = { null })
        assertFalse(r.outgoing)
        assertEquals("user 999", r.nick)
    }

    /** A whisper to yourself is legal; it should read as outgoing. */
    @Test
    fun selfWhisperIsOutgoing() {
        val r = WhisperResolver.resolve(from = me, to = me, myUserid = me, lookupNick = lookup)
        assertTrue(r.outgoing)
        assertEquals(me, r.otherId)
        assertEquals("myself", r.nick)
    }
}
