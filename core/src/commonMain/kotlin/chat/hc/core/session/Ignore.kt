package chat.hc.core.session

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind

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

    fun hides(m: ChatMessage, ignored: Set<String>): Boolean =
        ignored.isNotEmpty() && !m.isMine && m.kind in HIDDEN && m.nick in ignored
}
