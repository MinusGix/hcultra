package chat.hc.core.protocol

/**
 * User permission levels, from `hc/commands/utility/_UAC.js`.
 *
 * Comparisons are `>=`, so a level is a floor rather than an exact value.
 * Note `bot` (99) sits *below* `default` (100) — a bot is deliberately less
 * privileged than an ordinary user, not more.
 */
object Levels {
    const val ADMIN = 9_999_999
    const val MODERATOR = 999_999
    const val CHANNEL_OWNER = 99_999
    const val CHANNEL_MODERATOR = 9_999
    const val CHANNEL_TRUSTED = 8_999
    const val TRUSTED_USER = 500
    const val DEFAULT = 100
    const val BOT = 99

    fun isAdmin(level: Int) = level >= ADMIN
    fun isModerator(level: Int) = level >= MODERATOR

    /** Short label for the roster, or null for an ordinary user. */
    fun badge(level: Int): String? = when {
        level >= ADMIN -> "admin"
        level >= MODERATOR -> "mod"
        level >= CHANNEL_OWNER -> "owner"
        level >= CHANNEL_MODERATOR -> "ch-mod"
        level >= CHANNEL_TRUSTED -> "trusted"
        else -> null
    }
}
