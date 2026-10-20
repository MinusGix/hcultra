package chat.hc.core.session

import chat.hc.core.protocol.Outbound

/**
 * Slash commands for a server that leaves them to its web client.
 *
 * hack.chat before late 2018 parsed no commands at all: `/nick bob` sent as a
 * chat is broadcast to the channel as `/nick bob`. Its web client caught them
 * first, and an instance that kept running that server kept its own client's
 * list. This is the trollegle chatbox's (client.js as served in 2024), so
 * someone used to that page finds the same commands here and nothing typed
 * there ends up said out loud by accident:
 *
 * | command                 | what it does                                     |
 * |-------------------------|--------------------------------------------------|
 * | `/help`                 | lists these                                      |
 * | `/nick name`            | `changenick`                                     |
 * | `/ignore nick`          | ignores them, then shows the ignore list          |
 * | `/unignore nick`        | stops, then shows the list                        |
 * | `/ignorelist`           | the nicks and expressions being ignored           |
 * | `/clearignore`          | forgets every ignored nick                        |
 * | `/ignoreexpr re`        | hides every message matching `re`                 |
 * | `/ignoreexprfirst re`   | hides a nick's first message if it matches `re`   |
 * | `/kick nick`, `/ban nick` | the moderator commands of the same name        |
 *
 * Anything else is sent as it was typed, as that client does — `/me` included,
 * which the server broadcasts verbatim and every client shows as an action.
 * The one exception is a whisper: the server has none, so `/w bob hi` would be
 * posted to the whole channel, and someone who typed it meant the opposite.
 */
object ClientCommands {

    sealed interface Action {
        /** A command the server does understand, under its own name. */
        data class Send(val frame: Outbound) : Action

        data class Ignore(val nick: String) : Action
        data class Unignore(val nick: String) : Action
        data object ShowIgnored : Action
        data object ClearIgnored : Action

        /** [pattern] is already known to compile. */
        data class IgnoreExpression(val pattern: String, val firstOnly: Boolean) : Action

        /** Nothing to send; [text] is for the transcript alone. */
        data class Notice(val text: String) : Action
    }

    /** In the order the site's `/help` lists them. */
    val NAMES = listOf(
        "/help", "/nick", "/ignore", "/ignoreexpr", "/ignoreexprfirst",
        "/unignore", "/ignorelist", "/clearignore", "/ban", "/kick",
    )

    /**
     * What the site hides from a nick it has not heard from before: 48 or more
     * words in one message, the shape of a pasted wall of spam. Always on
     * there, so on here too, and listed by `/ignorelist`.
     */
    const val FIRST_MESSAGE_SPAM = """(\W+\w+){48,}"""

    private val WHISPERS = setOf("/w", "/whisper", "/r", "/reply")

    /**
     * What [text] asks for, or null to send it as a chat.
     *
     * Split as the site splits it, at the first space; the argument is trimmed,
     * which the site does not do, so that `/ignore bob ` ignores bob.
     */
    fun parse(text: String, channel: String): Action? {
        val name = text.substringBefore(' ')
        val arg = text.substringAfter(' ', "").trim()
        if (name in WHISPERS) {
            return Action.Notice("This server has no whispers, so that was not sent: it would have been posted to the channel.")
        }
        if (name !in NAMES) return null
        val needsArg = name != "/help" && name != "/ignorelist" && name != "/clearignore"
        if (needsArg && arg.isEmpty()) return Action.Notice("Usage: $name ${if (name.startsWith("/ignoreexpr")) "expression" else "nick"}")
        return when (name) {
            "/help" -> Action.Notice("Commands: " + NAMES.joinToString(" "))
            "/nick" -> Action.Send(Outbound.ChangeNick(channel, arg))
            "/ignore" -> Action.Ignore(arg)
            "/unignore" -> Action.Unignore(arg)
            "/ignorelist" -> Action.ShowIgnored
            "/clearignore" -> Action.ClearIgnored
            "/ignoreexpr", "/ignoreexprfirst" ->
                if (runCatching { Regex(arg) }.isSuccess) Action.IgnoreExpression(arg, firstOnly = name == "/ignoreexprfirst")
                else Action.Notice("Not a valid expression: $arg")
            "/kick" -> Action.Send(Outbound.Kick(channel = channel, nick = arg))
            "/ban" -> Action.Send(Outbound.Ban(channel = channel, nick = arg))
            else -> null
        }
    }

    /** The site's `/ignorelist`, word for word. */
    fun ignoreListText(nicks: Collection<String>, always: List<String>, first: List<String>): String =
        (if (nicks.isNotEmpty()) "Ignored: " + nicks.joinToString(", ") else "User ignore list is empty") +
            "\n" + (if (always.isNotEmpty()) "Ignored expressions for all messages: " + always.joinToString(", ") { "/$it/" } else "No always-ignored expressions") +
            "\n" + (if (first.isNotEmpty()) "Ignored expressions for first message: " + first.joinToString(", ") { "/$it/" } else "No expressions ignored on first message")

    /**
     * The expression filters for one server's channels, and who each channel
     * has already heard from — the site's `seenNicks`, which is what makes a
     * first message first.
     */
    class Filters {
        val always = mutableListOf<String>()
        val first = mutableListOf(FIRST_MESSAGE_SPAM)
        private val alwaysRegex = mutableListOf<Regex>()
        private val firstRegex = mutableListOf(Regex(FIRST_MESSAGE_SPAM))
        private val seen = HashMap<String, MutableSet<String>>()

        fun add(pattern: String, firstOnly: Boolean) {
            if (firstOnly) { first += pattern; firstRegex += Regex(pattern) }
            else { always += pattern; alwaysRegex += Regex(pattern) }
        }

        /**
         * Whether [nick]'s message in [channel] should go unseen; records them
         * as heard from when it should not. `find`, as JS's `match` is: a
         * match anywhere in the text counts.
         */
        fun hides(channel: String, nick: String, text: String): Boolean {
            val heard = seen.getOrPut(channel) { HashSet() }
            if (alwaysRegex.any { it.containsMatchIn(text) }) return true
            if (nick !in heard && firstRegex.any { it.containsMatchIn(text) }) return true
            heard += nick
            return false
        }

        fun forget(channel: String) {
            seen.remove(channel)
        }
    }
}
