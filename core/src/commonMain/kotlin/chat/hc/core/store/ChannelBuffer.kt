package chat.hc.core.store

import chat.hc.core.protocol.Inbound
import chat.hc.core.protocol.UpdateMode

enum class MessageKind { Chat, Emote, Whisper, WhisperSent, Info, Warning, Join, Leave }

enum class Delivery {
    /** Written to the socket; waiting for the server to echo it back. */
    Sending,

    /** Echoed back by the server, so it definitely reached the channel. */
    Sent,

    /** The socket rejected it outright — it definitely did not send. */
    Failed,

    /**
     * Sent, but never echoed: the connection dropped, or the server discarded
     * it without replying. Distinct from [Failed] on purpose — hack.chat keeps
     * no history, so a missed echo can never arrive late and we genuinely
     * cannot tell whether it was delivered. Calling that "failed" would invite
     * a duplicate resend of a message that did in fact go out.
     */
    Unconfirmed,
}

data class ChatMessage(
    val localId: Long,
    val kind: MessageKind,
    val nick: String = "",
    val userid: Long = 0L,
    val trip: String? = null,
    val text: String = "",
    val color: String? = null,
    val level: Int = 0,
    val at: Long = 0L,
    /** Correlates our optimistic echo, and targets `updateMessage` edits. */
    val customId: String? = null,
    val serverId: Long? = null,
    val isMine: Boolean = false,
    val delivery: Delivery = Delivery.Sent,
    /** True once a `complete` update arrives; bots stream until then. */
    val streamComplete: Boolean = true,
)

/**
 * Per-channel scrollback.
 *
 * Ephemeral by default: a bounded in-memory ring, owned by the foreground
 * service, never written to disk unless the user opts into persistence. This is
 * what lets the UI be destroyed and recreated — rotation, backgrounding,
 * notification tap — without losing the conversation, while still honouring
 * hack.chat's no-logs norm across app restarts.
 */
class ChannelBuffer(private val capacity: Int = 500) {
    private val messages = ArrayDeque<ChatMessage>()
    private var nextId = 1L

    /** customId -> localId, for `updateMessage` and for matching our own echo. */
    private val byCustomId = HashMap<String, Long>()

    val size: Int get() = messages.size

    fun snapshot(): List<ChatMessage> = messages.toList()

    fun add(message: ChatMessage): ChatMessage {
        val stamped = message.copy(localId = nextId++)
        messages.addLast(stamped)
        stamped.customId?.let { byCustomId[it] = stamped.localId }
        trim()
        return stamped
    }

    /**
     * Records a message we just sent, before the server echoes it, so the UI
     * updates instantly. [Delivery.Sending] until the echo lands.
     */
    fun addPending(text: String, customId: String, nick: String, userid: Long, at: Long): ChatMessage =
        add(
            ChatMessage(
                localId = 0,
                kind = MessageKind.Chat,
                nick = nick,
                userid = userid,
                text = text,
                at = at,
                customId = customId,
                isMine = true,
                delivery = Delivery.Sending,
            )
        )

    /**
     * Applies an inbound chat. If it carries a customId we already have pending,
     * this is our own echo: reconcile rather than duplicate.
     */
    fun applyChat(frame: Inbound.Chat, myUserid: Long?): ChatMessage {
        val pendingId = frame.customId?.let { byCustomId[it] }
        if (pendingId != null) {
            val idx = messages.indexOfFirst { it.localId == pendingId }
            // Accept the echo for an already-downgraded message too: a slow
            // round trip can outlive the timeout, and resolving it is far
            // better than appending a duplicate of what the user just sent.
            val pending = idx >= 0 &&
                (messages[idx].delivery == Delivery.Sending || messages[idx].delivery == Delivery.Unconfirmed)
            if (pending) {
                val reconciled = messages[idx].copy(
                    text = frame.text,
                    serverId = frame.id,
                    at = frame.time ?: messages[idx].at,
                    delivery = Delivery.Sent,
                    trip = frame.trip,
                    color = frame.color,
                    level = frame.level,
                )
                messages[idx] = reconciled
                return reconciled
            }
        }
        return add(
            ChatMessage(
                localId = 0,
                kind = MessageKind.Chat,
                nick = frame.nick,
                userid = frame.userid,
                trip = frame.trip,
                text = frame.text,
                color = frame.color,
                level = frame.level,
                at = frame.time ?: 0L,
                customId = frame.customId,
                serverId = frame.id,
                isMine = myUserid != null && frame.userid == myUserid,
                streamComplete = frame.customId == null,
            )
        )
    }

    /**
     * Bot streaming edits. Unknown customIds are ignored rather than buffered:
     * the target may have aged out of the ring, and a partial edit applied to
     * the wrong message is worse than a dropped one.
     */
    fun applyUpdate(frame: Inbound.UpdateMessage): ChatMessage? {
        val localId = byCustomId[frame.customId] ?: return null
        val idx = messages.indexOfFirst { it.localId == localId }
        if (idx < 0) return null
        val current = messages[idx]
        val updated = when (frame.updateMode) {
            UpdateMode.Overwrite -> current.copy(text = frame.text)
            UpdateMode.Append -> current.copy(text = current.text + frame.text)
            UpdateMode.Prepend -> current.copy(text = frame.text + current.text)
            UpdateMode.Complete -> current.copy(text = current.text + frame.text, streamComplete = true)
            UpdateMode.Unknown -> return null
        }
        messages[idx] = updated
        return updated
    }

    fun markFailed(customId: String) {
        val localId = byCustomId[customId] ?: return
        val idx = messages.indexOfFirst { it.localId == localId }
        if (idx >= 0) messages[idx] = messages[idx].copy(delivery = Delivery.Failed)
    }

    /**
     * Downgrades one still-pending message to [Delivery.Unconfirmed].
     * No-op if it was already reconciled, so a late echo always wins.
     *
     * @return true if anything changed, so callers can skip a redundant publish.
     */
    fun markUnconfirmed(customId: String): Boolean {
        val localId = byCustomId[customId] ?: return false
        val idx = messages.indexOfFirst { it.localId == localId }
        if (idx < 0 || messages[idx].delivery != Delivery.Sending) return false
        messages[idx] = messages[idx].copy(delivery = Delivery.Unconfirmed)
        return true
    }

    /**
     * Downgrades every still-pending message. Used when the connection drops:
     * the echoes those messages were waiting for can never arrive.
     */
    fun markAllPendingUnconfirmed(): Boolean {
        var changed = false
        for (i in messages.indices) {
            if (messages[i].delivery == Delivery.Sending) {
                messages[i] = messages[i].copy(delivery = Delivery.Unconfirmed)
                changed = true
            }
        }
        return changed
    }

    fun clear() {
        messages.clear()
        byCustomId.clear()
    }

    private fun trim() {
        while (messages.size > capacity) {
            val dropped = messages.removeFirst()
            dropped.customId?.let { byCustomId.remove(it) }
        }
    }
}
