package chat.hc.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Wire codec for the hack.chat v2 protocol.
 *
 * Decoding dispatches on `cmd` by hand rather than through a polymorphic
 * serializer so that an unrecognised command degrades to [Inbound.Unknown]
 * instead of throwing. The live server runs commands the public source does not
 * have, and gains more at each deploy, so this is a hard requirement rather
 * than defensiveness.
 */
object FrameCodec {

    /**
     * `classDiscriminator = "cmd"` is what actually puts the command name on
     * the wire for outbound frames — the `cmd` properties on [Outbound] are
     * getter-only and carry no backing field, so they are never serialized.
     */
    private val json = Json {
        classDiscriminator = "cmd"
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    /** Lenient reader for inbound payloads; server fields drift between deploys. */
    private val reader = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    fun encode(frame: Outbound): String = json.encodeToString(Outbound.serializer(), frame)

    fun decode(text: String): Inbound {
        val root = reader.parseToJsonElement(text).jsonObject
        val cmd = root["cmd"]?.jsonPrimitive?.content
            ?: return Inbound.Unknown("", root, timeOf(root))

        return runCatching {
            when (cmd) {
                "session" -> reader.decodeFromJsonElement(Inbound.Session.serializer(), root)
                "onlineSet" -> reader.decodeFromJsonElement(Inbound.OnlineSet.serializer(), root)
                "onlineAdd" -> reader.decodeFromJsonElement(Inbound.OnlineAdd.serializer(), root)
                "onlineRemove" -> reader.decodeFromJsonElement(Inbound.OnlineRemove.serializer(), root)
                "updateUser" -> reader.decodeFromJsonElement(Inbound.UpdateUser.serializer(), root)
                "chat" -> reader.decodeFromJsonElement(Inbound.Chat.serializer(), root)
                "emote" -> reader.decodeFromJsonElement(Inbound.Emote.serializer(), root)
                "updateMessage" -> reader.decodeFromJsonElement(Inbound.UpdateMessage.serializer(), root)
                "whisper" -> reader.decodeFromJsonElement(Inbound.Whisper.serializer(), root)
                "invite" -> reader.decodeFromJsonElement(Inbound.Invite.serializer(), root)
                "info" -> reader.decodeFromJsonElement(Inbound.Info.serializer(), root)
                "warn" -> reader.decodeFromJsonElement(Inbound.Warn.serializer(), root)
                "captcha" -> reader.decodeFromJsonElement(Inbound.Captcha.serializer(), root)
                "passwordreq" -> reader.decodeFromJsonElement(Inbound.PasswordReq.serializer(), root)
                else -> Inbound.Unknown(cmd, root, timeOf(root))
            }
        }.getOrElse {
            // A known cmd whose shape we failed to parse is still better kept
            // than dropped: it may carry a field we have not modelled yet.
            Inbound.Unknown(cmd, root, timeOf(root))
        }
    }

    private fun timeOf(o: JsonObject): Long? = o["time"]?.jsonPrimitive?.longOrNull
}

/**
 * Server-enforced limits. Exceeding these is worse than an error: the server
 * drops the message and charges rate-limit weight **without replying**, so the
 * client sees a message that simply never arrives.
 */
object Limits {
    /**
     * chat.js MAX_MESSAGE_ID_LENGTH. A longer customId costs `frisk(socket, 13)`
     * against a threshold of 25 — two of them inside one 30s halflife and the
     * user is rate-limited, with no server feedback explaining why.
     */
    const val MAX_CUSTOM_ID_LENGTH = 6
}

/**
 * Server error/notice ids we branch on, from hc/commands/utility/_Constants.js.
 * Ranges are base-10 blocks: Global 10, Captcha 20, Join 30, Channel 40,
 * Invite 50, Session 60, … Whisper 170.
 *
 * Branch on these rather than on `text` — the prose changes, and the same
 * message is reused across commands.
 */
object ErrorId {
    /** Global.RATELIMIT. Also what a too-fast `changenick` returns. */
    const val RATELIMIT = 11
    const val UNKNOWN_USER = 12
    const val PERMISSION = 13

    /**
     * Global.INTERNAL_ERROR — also what CommandManager raises for a missing
     * `requiredData` field, e.g. a `whisper` sent without `nick`.
     */
    const val INTERNAL_ERROR = 14

    /** Captcha.NO_REJOIN: a token restore refused because the channel has a captcha. */
    const val CAPTCHA_NO_REJOIN = 22

    /** Captcha.BAD_CAPTCHA. The challenge is spent; a fresh `join` gets a new one. */
    const val BAD_CAPTCHA = 23

    /**
     * The Join block. Renumbered in the 2026-10 server update: 33 used to be
     * "Joining more than one channel is not currently supported" and is now
     * NAME_TAKEN.
     */
    const val JOIN_INVALID_NICK = 31
    const val JOIN_ALREADY_JOINED = 32
    const val JOIN_NAME_TAKEN = 33
    const val JOIN_CHANNEL_LOCKED = 34
    const val JOIN_LEGACY_RESTRICT = 35

    /** LockRoom.NO_REJOIN: a token restore refused because the channel is locked. */
    const val LOCKROOM_NO_REJOIN = 204

    /**
     * LockRoom.INVALID_PASSWORD — what a wrong channel password gets. Like
     * [BAD_CAPTCHA] it spends the challenge.
     */
    const val INVALID_PASSWORD = 205

    /** Password.NO_AUTO_JOIN: a token restore refused because the channel has a password. */
    const val PASSWORD_NO_REJOIN = 221

    /**
     * Not in `_Constants.js`: hackchat-server's MainServer sends it, through
     * the `socketreply` command, in place of handling *any* frame while the
     * address is over the rate threshold or arrested — `session` included.
     */
    const val BLOCKED = 987654323

    /** Info.Core.MOTD, delivered on every join. Observed as 1304 on live. */
    const val MOTD = 1304
}
