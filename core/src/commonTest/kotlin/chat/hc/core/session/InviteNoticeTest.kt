package chat.hc.core.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The wording is the server's own, lifted from the v1 prose in
 * `_LegacyFunctions.js`, so that a v1 and a v2 client shown the same invite
 * read the same sentence.
 */
class InviteNoticeTest {

    @Test
    fun incomingNamesTheInviter() {
        assertEquals("bob invited you to ?somewhere", InviteNotice.line(false, "bob", "somewhere"))
    }

    @Test
    fun outgoingNamesTheTarget() {
        assertEquals("You invited bob to ?somewhere", InviteNotice.line(true, "bob", "somewhere"))
    }

    /** A notification shows the nick already; the text must not repeat it. */
    @Test
    fun theAlertTextLeavesTheInviterToTheNotification() {
        assertEquals("invited you to ?somewhere", InviteNotice.alert("somewhere"))
    }

    /**
     * Unreachable against a conforming server — `getChannel` substitutes a
     * random name — but a bare `?` would linkify into a link to nowhere, and
     * `probe/fakeserver.mjs` exists precisely because the real one will not
     * produce this on demand.
     */
    @Test
    fun aMissingChannelDoesNotBecomeAnEmptyLink() {
        val line = InviteNotice.line(false, "bob", "")
        assertFalse("?" in line, "a blank channel produced a dangling reference: $line")
        assertEquals("bob invited you to an unnamed channel", line)
        assertEquals("invited you to an unnamed channel", InviteNotice.alert("  "))
    }
}
