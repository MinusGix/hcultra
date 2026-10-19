package chat.hc.core.render

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind

/**
 * The image URLs a run of messages could embed — what the image cache keeps
 * for as long as some message still holds it.
 *
 * Read off the markdown rather than the rendered page: a message scrolled out
 * of the drawn window is still in the chat, and its picture is exactly the one
 * a reader scrolling back up will want again.
 *
 * Generous on purpose. A URL listed here that the page never embeds costs only
 * a cache entry kept a little longer; one missed costs a refetch, which is no
 * worse than having no cache at all. So this does not re-check the whitelist,
 * and it accepts `![…](…)` wherever it appears, code spans included.
 */
object ImageRefs {

    /** `![alt](url` — the URL up to whitespace, `)` or `>`, an optional `<` before it. */
    private val image = Regex("""!\[[^\]]*]\(\s*<?([^\s)>]+)""")

    fun of(text: String): Sequence<String> {
        if ("![" !in text) return emptySequence()
        return image.findAll(text).map { it.groupValues[1] }
    }

    fun of(messages: Iterable<ChatMessage>): Set<String> {
        val urls = HashSet<String>()
        for (m in messages) {
            // Join and leave have no text of their own to render.
            if (m.kind == MessageKind.Join || m.kind == MessageKind.Leave) continue
            urls += of(m.text)
        }
        return urls
    }
}
