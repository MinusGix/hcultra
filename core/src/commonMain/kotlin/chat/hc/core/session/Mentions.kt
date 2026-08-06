package chat.hc.core.session

/**
 * Whether a line of chat addresses you by name.
 *
 * `@nick` and nothing else. Not the bare nick: "has anyone seen bob" is about
 * bob rather than to him, and a common nick would otherwise buzz the phone all
 * day. Not the trip either — a trip identifies you but nobody types one to get
 * your attention, so matching it would only ever produce false positives from
 * people quoting a transcript.
 *
 * Matching is case-insensitive. hack.chat treats nicks as unique without regard
 * to case, so `@Bob` cannot reach anyone but bob, and people type what looks
 * right at the start of a sentence rather than what the roster says.
 */
object Mentions {

    /** hack.chat's own nick charset (`[a-zA-Z0-9_]{1,24}`). */
    private fun Char.isNickChar(): Boolean = this == '_' || isLetterOrDigit()

    /**
     * Both edges have to be checked, and for different reasons.
     *
     * After the nick: `@bobby` is a different person from `@bob`, and a prefix
     * match would deliver every one of bobby's mentions to bob as well.
     *
     * Before the `@`: an email address is the case that matters — `mail
     * ops@bob.example` is not somebody calling bob, and paths and handles from
     * other sites read the same way.
     */
    fun mentions(text: String, nick: String): Boolean {
        if (nick.isEmpty()) return false
        val needle = "@$nick"
        var from = 0
        while (from <= text.length - needle.length) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) return false
            val before = text.getOrNull(at - 1)
            val after = text.getOrNull(at + needle.length)
            if (before?.isNickChar() != true && after?.isNickChar() != true) return true
            // Overlapping candidates are real: "@@bob" should still land.
            from = at + 1
        }
        return false
    }
}
