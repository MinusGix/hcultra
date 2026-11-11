package chat.hc.core.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A transcript as text, for a [chat.hc.core.session.TranscriptStore] to keep.
 *
 * Its own shape rather than [ChatMessage] annotated, so that what is written to
 * disk is a choice: the local id is the buffer's to hand out again, and
 * [ChatMessage.veiled] belongs to the view. Every field has a default, so an
 * older file still reads after one is added, and an unreadable one reads as
 * nothing rather than taking the channel down with it.
 */
object TranscriptCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(messages: List<ChatMessage>): String =
        json.encodeToString(Stored.Transcript.serializer(), Stored.Transcript(messages.map(Stored::of)))

    fun decode(text: String): List<ChatMessage> =
        runCatching { json.decodeFromString(Stored.Transcript.serializer(), text) }
            .getOrNull()
            ?.messages
            ?.mapNotNull { it.toMessage() }
            .orEmpty()

    @Serializable
    private data class Stored(
        val kind: String = "",
        val nick: String = "",
        val userid: Long = 0L,
        val trip: String? = null,
        val text: String = "",
        val color: String? = null,
        val flair: String? = null,
        val level: Int = 0,
        val at: Long = 0L,
        val customId: String? = null,
        val serverId: Long? = null,
        val isMine: Boolean = false,
        val delivery: String = Delivery.Sent.name,
    ) {
        @Serializable
        data class Transcript(val messages: List<Stored> = emptyList())

        fun toMessage(): ChatMessage? {
            val kind = MessageKind.entries.firstOrNull { it.name == kind } ?: return null
            return ChatMessage(
                localId = 0,
                kind = kind,
                nick = nick,
                userid = userid,
                trip = trip,
                text = text,
                color = color,
                flair = flair,
                level = level,
                at = at,
                customId = customId,
                serverId = serverId,
                isMine = isMine,
                delivery = Delivery.entries.firstOrNull { it.name == delivery } ?: Delivery.Sent,
            )
        }

        companion object {
            fun of(m: ChatMessage) = Stored(
                kind = m.kind.name,
                nick = m.nick,
                userid = m.userid,
                trip = m.trip,
                text = m.text,
                color = m.color,
                flair = m.flair,
                level = m.level,
                at = m.at,
                customId = m.customId,
                serverId = m.serverId,
                isMine = m.isMine,
                delivery = m.delivery.name,
            )
        }
    }
}
