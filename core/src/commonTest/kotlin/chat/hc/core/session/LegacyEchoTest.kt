package chat.hc.core.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a v1 server echoes for what we sent, which is how our own message is
 * recognised there. Expected values are what hack.chat's `parseText` and the
 * `//` rule in `chat.js` give for the same input, run under node.
 */
class LegacyEchoTest {

    @Test
    fun blankLinesAtEitherEndAreStripped() {
        assertEquals("  hello", LegacyEcho.expected("\n\n  hello"))
        // Only from the first trailing newline: the space before it stays.
        assertEquals("hello ", LegacyEcho.expected("hello \n\n"))
    }

    @Test
    fun onlyWhitespaceBecomesNothing() {
        assertEquals("", LegacyEcho.expected("   "))
    }

    @Test
    fun runsOfNewlinesSqueezeToOneBlankLine() {
        assertEquals("a\n\nb", LegacyEcho.expected("a\n\n\n\nb"))
        assertEquals("line one\nline two", LegacyEcho.expected("line one\nline two"))
    }

    @Test
    fun leadingSpacesWithoutANewlineStay() {
        assertEquals("  indented", LegacyEcho.expected("  indented"))
    }

    /** `//` is how a line that starts with a slash is sent as chat; the server drops one. */
    @Test
    fun aDoubledSlashLosesOne() {
        assertEquals("/not a command", LegacyEcho.expected("//not a command"))
        assertEquals("a // b", LegacyEcho.expected("a // b"))
    }
}
