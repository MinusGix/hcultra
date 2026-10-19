package chat.hc.core.session

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind

/**
 * Who the user is ignoring: by nick, by trip, or both.
 *
 * A nick is all most people have, but it is also all it takes to get out from
 * under one: change it and you are someone else. A trip follows whoever holds
 * the password, whatever they call themselves, so ignoring one is what sticks
 * to a person rather than a name. Either match is enough.
 */
data class IgnoreList(
    val nicks: Set<String> = emptySet(),
    val trips: Set<String> = emptySet(),
) {
    fun isEmpty(): Boolean = nicks.isEmpty() && trips.isEmpty()

    fun matches(nick: String, trip: String?): Boolean =
        nick in nicks || (!trip.isNullOrBlank() && trip in trips)
}

/**
 * Which messages an ignore hides. One rule, shared by the transcript and the
 * notification's recent lines, so the two cannot disagree about what was said.
 *
 * What they say — to the room, as an emote, or to you — but not that they came
 * and went: the join or leave line is where their nick can still be held to
 * stop ignoring them. See [SessionManager.ignored].
 */
object Ignore {
    private val HIDDEN = setOf(MessageKind.Chat, MessageKind.Emote, MessageKind.Whisper)

    fun hides(m: ChatMessage, ignored: IgnoreList): Boolean =
        !ignored.isEmpty() && !m.isMine && m.kind in HIDDEN && ignored.matches(m.nick, m.trip)
}

/**
 * What the transcript does with a line [Ignore.hides].
 *
 * Only the transcript: unread counts, alerts and the notification's recent
 * lines leave an ignored sender out whichever this is, since a mode is about
 * how their lines look when you are already reading, not whether they may
 * interrupt you.
 */
enum class IgnoreMode(val label: String) {
    /** Gone from the transcript, as ignoring has always been. */
    Hide("Hide"),

    /** Nick shown, text blacked out until tapped. */
    Spoiler("Black out"),

    /** Nick shown, text swapped for [IgnoreMode.DEFAULT_FUN_TEXT] or the user's own, until tapped. */
    Fun("Replace"),
    ;

    companion object {
        val DEFAULT = Hide

        const val DEFAULT_FUN_TEXT = "I am dumb"

        fun from(name: String?): IgnoreMode = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
