package chat.hc.core.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * A user as the server describes them in `onlineSet` / `onlineAdd` / `updateUser`.
 *
 * `userid` is the identity that survives a session restore — nick, colour and
 * even level can change underneath it, so it is the only safe key for presence
 * and message attribution.
 */
@Serializable
data class User(
    val nick: String = "",
    val userid: Long = 0L,
    val trip: String? = null,
    val hash: String? = null,
    val level: Int = 0,
    val color: String? = null,
    @Serializable(with = FlairSerializer::class)
    val flair: String? = null,
    val uType: String? = null,
    val isBot: Boolean = false,
    val channel: String? = null,
    val isme: Boolean = false,
    val online: Boolean = true,
)

/** Frames the server sends us. */
sealed interface Inbound {
    val time: Long?

    @Serializable
    data class Session(
        val restored: Boolean = false,
        val token: String = "",
        val channels: List<String> = emptyList(),
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class OnlineSet(
        val users: List<User> = emptyList(),
        val nicks: List<String> = emptyList(),
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class OnlineAdd(
        val nick: String = "",
        val userid: Long = 0L,
        val trip: String? = null,
        val hash: String? = null,
        val level: Int = 0,
        val color: String? = null,
        @Serializable(with = FlairSerializer::class)
        val flair: String? = null,
        val uType: String? = null,
        val isBot: Boolean = false,
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound {
        fun toUser() = User(nick, userid, trip, hash, level, color, flair, uType, isBot, channel)
    }

    @Serializable
    data class OnlineRemove(
        val nick: String = "",
        val userid: Long = 0L,
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound

    /**
     * Sent instead of an `onlineAdd` when a socket with the same `userid` is
     * already present — i.e. the silent make-before-break handoff path.
     */
    @Serializable
    data class UpdateUser(
        val nick: String = "",
        val userid: Long = 0L,
        val trip: String? = null,
        val hash: String? = null,
        val level: Int = 0,
        val color: String? = null,
        @Serializable(with = FlairSerializer::class)
        val flair: String? = null,
        val uType: String? = null,
        val isBot: Boolean = false,
        val online: Boolean = true,
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class Chat(
        val nick: String = "",
        val userid: Long = 0L,
        val text: String = "",
        val channel: String? = null,
        val level: Int = 0,
        val trip: String? = null,
        val color: String? = null,
        @Serializable(with = FlairSerializer::class)
        val flair: String? = null,
        val uType: String? = null,
        val customId: String? = null,
        val id: Long? = null,
        val admin: Boolean = false,
        val mod: Boolean = false,
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class Emote(
        val nick: String = "",
        val userid: Long = 0L,
        val text: String = "",
        val channel: String? = null,
        val trip: String? = null,
        override val time: Long? = null,
    ) : Inbound

    /**
     * Bot streaming updates, keyed by the `customId` of the original `chat`.
     * @see UpdateMode
     */
    @Serializable
    data class UpdateMessage(
        val mode: String = "overwrite",
        val text: String = "",
        val customId: String = "",
        val userid: Long = 0L,
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound {
        val updateMode: UpdateMode get() = UpdateMode.from(mode)
    }

    @Serializable
    data class Whisper(
        val from: Long = 0L,
        val to: Long = 0L,
        val text: String = "",
        val channel: String? = null,
        val trip: String? = null,
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class Invite(
        val from: Long = 0L,
        val to: Long = 0L,
        val inviteChannel: String = "",
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound

    /**
     * Carries MOTD, and — for v1 sockets — whisper/invite rendered as prose.
     * We always negotiate v2, so `type` should never appear; if it does, we are
     * accidentally speaking the legacy dialect.
     */
    @Serializable
    data class Info(
        val text: String = "",
        val id: Int? = null,
        val type: String? = null,
        val from: String? = null,
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound

    @Serializable
    data class Warn(
        val text: String = "",
        val id: Int? = null,
        val channel: String? = null,
        override val time: Long? = null,
    ) : Inbound

    /**
     * Any frame we do not model. The live server runs commands absent from the
     * public source (`bomb`, `uwuify`, the wallet set), and more may appear at
     * any deploy, so an unknown `cmd` must never be fatal.
     */
    data class Unknown(
        val cmd: String,
        val raw: JsonObject,
        override val time: Long? = null,
    ) : Inbound
}

/** How an [Inbound.UpdateMessage] mutates the message it targets. */
enum class UpdateMode {
    Overwrite, Append, Prepend, Complete, Unknown;

    companion object {
        fun from(s: String) = when (s.lowercase()) {
            "overwrite" -> Overwrite
            "append" -> Append
            "prepend" -> Prepend
            "complete" -> Complete
            else -> Unknown
        }
    }
}

/**
 * Commands we send.
 *
 * Two contracts the server enforces that are easy to get wrong, both verified
 * against live (see probe/FINDINGS.md §2):
 *  - a v2 socket must name `channel` explicitly on targeted commands; unlike v1
 *    it is not filled in from the socket;
 *  - [Whisper] requires `nick` and rejects a userid-only payload, while
 *    [Invite] requires a numeric `userid` and silently drops a nick-only one.
 */
@Serializable
sealed class Outbound {
    abstract val cmd: String

    /**
     * Must be the first frame on every socket: `session.js` sets hcProtocol = 2
     * before it validates the token, so even a tokenless session frame is what
     * opts us out of the legacy dialect. Sending `join` first is unrecoverable
     * for the life of that socket.
     */
    @Serializable
    @SerialName("session")
    data class Session(val token: String? = null) : Outbound() {
        override val cmd: String get() = "session"
    }

    @Serializable
    @SerialName("join")
    data class Join(
        val channel: String,
        val nick: String,
        val pass: String? = null,
    ) : Outbound() {
        override val cmd: String get() = "join"
    }

    @Serializable
    @SerialName("chat")
    data class Chat(
        val text: String,
        val customId: String? = null,
    ) : Outbound() {
        override val cmd: String get() = "chat"
    }

    @Serializable
    @SerialName("emote")
    data class Emote(val text: String) : Outbound() {
        override val cmd: String get() = "emote"
    }

    /** Requires `nick`; a userid-only payload is rejected with warn id 14. */
    @Serializable
    @SerialName("whisper")
    data class Whisper(
        val channel: String,
        val nick: String,
        val text: String,
    ) : Outbound() {
        override val cmd: String get() = "whisper"
    }

    /** Requires numeric `userid`; a nick-only payload is silently dropped. */
    @Serializable
    @SerialName("invite")
    data class Invite(
        val channel: String,
        val userid: Long,
        val to: String? = null,
    ) : Outbound() {
        override val cmd: String get() = "invite"
    }

    @Serializable
    @SerialName("changenick")
    data class ChangeNick(val nick: String) : Outbound() {
        override val cmd: String get() = "changenick"
    }

    @Serializable
    @SerialName("changecolor")
    data class ChangeColor(val color: String) : Outbound() {
        override val cmd: String get() = "changecolor"
    }

    @Serializable
    @SerialName("updateMessage")
    data class UpdateMessage(
        val mode: String,
        val text: String,
        val customId: String,
    ) : Outbound() {
        override val cmd: String get() = "updateMessage"
    }

    @Serializable
    @SerialName("ping")
    data object Ping : Outbound() {
        override val cmd: String get() = "ping"
    }

    // --- moderation -------------------------------------------------------
    //
    // These are API-only: unlike /me or /w, most have no text hook, so they
    // cannot be typed into the composer at all. On the website they are
    // reached from the browser console.
    //
    // v2 targets by numeric `userid` and requires an explicit `channel`,
    // except `speak`, which keys on the target's `hash`.

    /** Requires channel-moderator. Optional `to` re-homes them to another channel. */
    @Serializable
    @SerialName("kick")
    data class Kick(
        val channel: String,
        val userid: Long,
        val to: String? = null,
    ) : Outbound() {
        override val cmd: String get() = "kick"
    }

    /** Requires global moderator. */
    @Serializable
    @SerialName("ban")
    data class Ban(
        val channel: String,
        val userid: Long,
    ) : Outbound() {
        override val cmd: String get() = "ban"
    }

    /** Muzzle: their messages are silently dropped. Requires global moderator. */
    @Serializable
    @SerialName("dumb")
    data class Muzzle(
        val channel: String,
        val userid: Long,
        val allies: List<String>? = null,
    ) : Outbound() {
        override val cmd: String get() = "dumb"
    }

    /** Un-muzzle. Keyed by hash, not userid. Requires global moderator. */
    @Serializable
    @SerialName("speak")
    data class Unmuzzle(val hash: String) : Outbound() {
        override val cmd: String get() = "speak"
    }
}
