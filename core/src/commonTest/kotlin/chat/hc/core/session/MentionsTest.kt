package chat.hc.core.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A mention is the one thing in a channel worth buzzing a phone for, so both
 * directions of error are expensive: a miss means the notification the feature
 * exists for never arrives, and a false positive trains the user to turn it off.
 */
class MentionsTest {

    private fun mentions(text: String, nick: String = "bob") = Mentions.mentions(text, nick)

    @Test
    fun plainMentionMatches() {
        assertTrue(mentions("@bob have you seen this"))
        assertTrue(mentions("have you seen this @bob"))
        assertTrue(mentions("well @bob would know"))
    }

    /** Punctuation is not part of a nick, so it must not break the match. */
    @Test
    fun trailingPunctuationStillMatches() {
        assertTrue(mentions("@bob, thoughts?"))
        assertTrue(mentions("ask @bob."))
        assertTrue(mentions("(@bob)"))
        assertTrue(mentions("@bob"))
    }

    /** The whole point of requiring the sigil. */
    @Test
    fun bareNickIsNotAMention() {
        assertFalse(mentions("has anyone seen bob"))
        assertFalse(mentions("bob: this is about you"))
    }

    /**
     * The failure that would make the feature useless in a channel with similar
     * nicks: bob must not receive everything addressed to bobby.
     */
    @Test
    fun longerNickIsADifferentPerson() {
        assertFalse(mentions("@bobby knows"))
        assertFalse(mentions("@bob_ knows"))
        assertFalse(mentions("@bob2 knows"))
        assertTrue(mentions("@bobby and @bob both know"))
    }

    /** An address is not a summons. */
    @Test
    fun emailIsNotAMention() {
        assertFalse(mentions("mail ops@bob.example"))
        assertFalse(mentions("see foo@bob"))
    }

    /** hack.chat nicks are unique without regard to case; people type sentence case. */
    @Test
    fun matchIsCaseInsensitive() {
        assertTrue(mentions("@Bob you there"))
        assertTrue(mentions("@BOB you there"))
        assertTrue(mentions("@bob you there", nick = "Bob"))
    }

    @Test
    fun matchesLaterOccurrenceWhenTheFirstIsEmbedded() {
        assertFalse(mentions("x@bob"))
        assertTrue(mentions("x@bob and @bob"))
        // Overlapping candidates: the second @ starts a valid mention.
        assertTrue(mentions("@@bob"))
    }

    @Test
    fun emptyNickNeverMatches() {
        assertFalse(mentions("@ hello", nick = ""))
    }

    /** Underscores are legal in hack.chat nicks and must not read as a boundary. */
    @Test
    fun underscoreNicksMatchWhole() {
        assertTrue(mentions("@a_b hi", nick = "a_b"))
        assertFalse(mentions("@a_bc hi", nick = "a_b"))
    }
}
