package chat.hc.core.session

import chat.hc.core.protocol.Levels
import chat.hc.core.protocol.Outbound
import chat.hc.core.protocol.User

/**
 * Moderation actions and who may perform them.
 *
 * Two reasons this is modelled rather than left to the UI:
 *
 * 1. **The gates differ per command.** `kick` needs only channel-moderator
 *    (9 999) while `ban`, `dumb` and `speak` need global moderator (999 999).
 *    Assuming one threshold for "mod actions" would be wrong in both
 *    directions.
 * 2. **Failing a gate is expensive.** Each rejected attempt costs
 *    `frisk(socket, 10)` against a threshold of 25, so offering an action the
 *    user cannot perform would rate-limit them after two taps, with the server
 *    replying nothing at all. Gating client-side is not cosmetic.
 *
 * These commands are API-only — unlike `/me` or `/w`, most have no text hook,
 * so they cannot be typed into the composer. On the website they are reached
 * through the browser console.
 */
enum class ModAction(val label: String, val minimumLevel: Int, val destructive: Boolean) {
    /** Boot from the channel; they can rejoin. */
    Kick("Kick", Levels.CHANNEL_MODERATOR, destructive = true),

    /** Blocks the address; survives their leaving. */
    Ban("Ban", Levels.MODERATOR, destructive = true),

    /** Their messages are silently dropped — they are not told. */
    Muzzle("Muzzle", Levels.MODERATOR, destructive = true),

    Unmuzzle("Unmuzzle", Levels.MODERATOR, destructive = false);

    fun permitted(myLevel: Int): Boolean = myLevel >= minimumLevel
}

object Moderation {

    /**
     * Actions [me] may take against [target] in [channel].
     *
     * Excludes actions aimed at yourself — kicking yourself is legal on the
     * server and never intended — and hides [ModAction.Unmuzzle] behind a
     * known hash, since `speak` keys on hash rather than userid.
     *
     * Also excludes targets at or above our own level. Upstream refuses those
     * explicitly — `kick.js` ("Cannot kick other users with the same level,
     * how rude") and `dumb.js` both test `target.level >= socket.level` — so
     * offering the action would be an affordance the server always rejects.
     * Note the comparison is `>=`: a moderator cannot act on another moderator,
     * not merely on someone senior.
     */
    fun available(me: User?, target: User): List<ModAction> {
        val level = me?.level ?: return emptyList()
        if (me.userid == target.userid) return emptyList()
        if (target.level >= level) return emptyList()
        return ModAction.entries.filter { action ->
            action.permitted(level) &&
                (action != ModAction.Unmuzzle || !target.hash.isNullOrBlank())
        }
    }

    /**
     * Builds the frame for an action, or null when the target lacks what the
     * command needs (only [ModAction.Unmuzzle], which requires a hash).
     */
    fun frameFor(action: ModAction, channel: String, target: User): Outbound? = when (action) {
        ModAction.Kick -> Outbound.Kick(channel = channel, userid = target.userid)
        ModAction.Ban -> Outbound.Ban(channel = channel, userid = target.userid)
        ModAction.Muzzle -> Outbound.Muzzle(channel = channel, userid = target.userid)
        ModAction.Unmuzzle -> target.hash?.takeIf { it.isNotBlank() }?.let { Outbound.Unmuzzle(it) }
    }
}
