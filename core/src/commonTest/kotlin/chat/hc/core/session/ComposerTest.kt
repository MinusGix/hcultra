package chat.hc.core.session

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cases taken from a live probe of hack.chat 2.2.3b; see [Composer] for the
 * observed responses. The point of these is that we never try to predict which
 * commands echo — only whether the server treats the text as a command at all.
 */
class ComposerTest {

    @Test
    fun commandsAreRecognised() {
        // Suppress the chat entirely (emote / info / warn instead).
        assertTrue(Composer.isSlashCommand("/me waves"))
        assertTrue(Composer.isSlashCommand("/w someone hi"))
        assertTrue(Composer.isSlashCommand("/whisper someone hi"))
        assertTrue(Composer.isSlashCommand("/nick newname"))
        assertTrue(Composer.isSlashCommand("/myhash"))
        assertTrue(Composer.isSlashCommand("/notacommand foo"))
        // Rewritten but still broadcast — still a command as far as we care,
        // and its echo simply arrives as an ordinary message.
        assertTrue(Composer.isSlashCommand("/shrug hello"))
    }

    /** `//` is the server's escape for a literal leading slash. */
    @Test
    fun doubleSlashIsAnOrdinaryMessage() {
        assertFalse(Composer.isSlashCommand("//literal slash message"))
        assertFalse(Composer.isSlashCommand("//"))
    }

    @Test
    fun ordinaryTextIsNotACommand() {
        assertFalse(Composer.isSlashCommand("hello"))
        assertFalse(Composer.isSlashCommand("a / in the middle"))
        assertFalse(Composer.isSlashCommand(""))
        assertFalse(Composer.isSlashCommand("http://example.com"))
    }

    /**
     * The server does NOT trim before matching: its hooks test the raw text and
     * `parseText` only strips newlines. Verified on live — `"  /me waves"` is
     * echoed back as an ordinary chat with our customId, so leading whitespace
     * genuinely disarms the command and we must not "helpfully" trim it.
     */
    @Test
    fun leadingWhitespaceDisarmsTheCommand() {
        assertFalse(Composer.isSlashCommand("  /me waves"))
        assertFalse(Composer.isSlashCommand("  //literal"))
    }

    /**
     * Bare `/me` (no trailing space) misses the hook and is rejected by
     * finalCmdCheck as an unknown command — still no echo, so still a command.
     */
    @Test
    fun bareCommandWithoutArgumentsIsStillACommand() {
        assertTrue(Composer.isSlashCommand("/me"))
    }
}
