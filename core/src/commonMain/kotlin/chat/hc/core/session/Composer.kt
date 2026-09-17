package chat.hc.core.session

/**
 * Rules for what the client does with composed text.
 *
 * hack.chat parses `/` commands **server-side**, through `in`/`chat` hooks that
 * each module registers (`/me`, `/w`, `/nick`, …), with `chat.js#finalCmdCheck`
 * rejecting anything unrecognised. So we deliberately do *not* parse commands
 * here: sending the raw text gives exact parity with the site, and a command
 * added upstream works without touching this client.
 *
 * What we do need to know is whether to expect our own message back, because
 * that decides whether to show an optimistic bubble. Verified against live:
 *
 * | input           | server sends            | echoes our customId |
 * |-----------------|-------------------------|---------------------|
 * | `/me waves`     | `emote`                 | no                  |
 * | `/shrug hi`     | `chat`, text rewritten  | **yes**             |
 * | `//literal`     | `chat`, text `/literal` | **yes**             |
 * | `/notacommand`  | `warn` id 16            | no                  |
 * | `/myhash`       | `info` id 1302          | no                  |
 *
 * Since `/shrug` echoes and `/me` does not, "starts with a slash" cannot
 * predict the outcome — so we never guess. Slash commands are sent without a
 * customId and without an optimistic bubble, and whatever the server sends back
 * stands on its own. A command that happens to echo simply appears when it
 * arrives instead of instantly.
 */
object Composer {

    /**
     * True when the server will treat this as a command rather than a message.
     *
     * Matches the server byte for byte: the hooks test `startsWith('/me ')` on
     * the raw text, and `parseText` only strips newlines, never leading spaces.
     * Verified on live — `"  /me waves"` comes back as an ordinary chat with our
     * customId intact, so trimming here would misclassify it.
     *
     * `//` is the server's escape for a literal leading slash and becomes an
     * ordinary chat, so it is not a command.
     */
    fun isSlashCommand(text: String): Boolean =
        text.startsWith("/") && !text.startsWith("//")
}

/**
 * Works out who a whisper involves and which way it went.
 *
 * The server sends the **same** frame to both parties —
 * `{cmd:'whisper', channel, from, to, text}` — so direction is only knowable by
 * comparing `from` against our own userid. Both ends are userids, never nicks,
 * so they must be resolved against the roster; do it on receipt, while the
 * sender is still present, because they may leave before the view redraws.
 */
object WhisperResolver {

    data class Resolved(
        /** True when we sent it: the frame is the echo of our own whisper. */
        val outgoing: Boolean,
        /** The other party — recipient if outgoing, sender if incoming. */
        val otherId: Long,
        val nick: String,
    )

    fun resolve(
        from: Long,
        to: Long,
        myUserid: Long?,
        lookupNick: (Long) -> String?,
    ): Resolved {
        val outgoing = myUserid != null && from == myUserid
        val otherId = if (outgoing) to else from
        // A userid is a poor label, but it beats an empty nick and makes the
        // "they already left" case visible rather than mysterious.
        val nick = lookupNick(otherId) ?: "user $otherId"
        return Resolved(outgoing, otherId, nick)
    }
}

/**
 * How an invite reads.
 *
 * A v2 socket is sent `cmd:'invite'` with no prose in it — only `from`, `to`
 * and `inviteChannel` — so the sentence has to be composed here. The wording is
 * the server's own: `_LegacyFunctions.js` spells it out for v1 clients, and
 * reusing that text verbatim is what keeps this client and the site saying the
 * same thing about the same event.
 */
object InviteNotice {

    /**
     * The transcript line.
     *
     * `?channel` is deliberately left literal rather than decorated: the
     * renderer linkifies it exactly as it does in any other message, and that
     * link is what makes the notice actionable — tapping it joins.
     */
    fun line(outgoing: Boolean, nick: String, inviteChannel: String): String =
        if (outgoing) "You invited $nick to ${target(inviteChannel)}"
        else "$nick invited you to ${target(inviteChannel)}"

    /**
     * The same news with the inviter left out, for an alert.
     *
     * A notification already shows who it came from — [Alert.nick] becomes the
     * sender of the line — so repeating the nick inside the text reads as a
     * stutter: "alice: alice invited you to ?x".
     */
    fun alert(inviteChannel: String): String = "invited you to ${target(inviteChannel)}"

    /**
     * A blank `inviteChannel` cannot come from a conforming server —
     * `invite.js#getChannel` substitutes a random name when the payload omits
     * one — but a bare `?` would linkify into a link to nowhere, so it is worth
     * not emitting one.
     */
    private fun target(inviteChannel: String): String =
        if (inviteChannel.isBlank()) "an unnamed channel" else "?$inviteChannel"
}
