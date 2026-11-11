package chat.hc.core.protocol

/**
 * Reads the v1 dialect into the v2 model the rest of the client speaks.
 *
 * Two kinds of server talk v1: today's hack.chat to a socket that sent `join`
 * first, and older instances that predate `session` altogether — 2019's
 * 2.1.91 is still running somewhere, and people use both. Measured against
 * that release (probe/legacy) and read off `_LegacyFunctions.js` for today's,
 * v1 differs from v2 in what it leaves out rather than in what it says:
 *
 * | event              | v1                                                  |
 * |--------------------|-----------------------------------------------------|
 * | `onlineSet`        | `nicks` only on 2019; no `users`, so no userids     |
 * | anyone, anywhere   | no `userid` on 2019 — a nick is the only identity   |
 * | emote              | `info`, `type:'emote'`, text `@nick …` on 2019      |
 * | whisper            | `info`, `type:'whisper'`, `from` a nick, prose text |
 * | invite             | `info`, `type:'invite'`, `from` a nick              |
 * | warn               | no `id` on 2019, so only the prose says what it is  |
 * | `trip`             | the string `"null"` for none on `onlineAdd`         |
 *
 * And a server from 2018 (the trollegle chatbox runs one) is older still. It
 * parses no slash commands at all, so `/me waves` is broadcast as a chat and
 * left to clients to show as an emote; an invite is an `info` with only an
 * `invite` key and prose; and `changenick` reaches everyone, the renamed
 * included, as the old nick leaving and the new one arriving.
 *
 * Everything is filled in only where it is missing, so a frame that is
 * already v2-shaped passes through untouched: which dialect a socket speaks is
 * not always known until its `onlineSet`, and this has to be safe either way.
 *
 * A userid a 2019 server never sent is derived from the nick ([idFor]). Nicks
 * are unique per channel there, compared without case, and the derived id is
 * negative so it cannot meet a real one. It is stable across reconnects, which
 * is what presence and message attribution need of it.
 *
 * One instance per connection, fed every frame in arrival order: it learns who
 * is who from the roster frames as they pass, which keeps a whisper resolvable
 * against the roster it was sent under rather than the one the UI has caught
 * up to.
 */
class LegacyAdapter {

    /** Our nick, as the latest `onlineSet` gave it. */
    private var me: String? = null

    /** Userids by nick, from every roster frame seen; kept after a leave. */
    private val ids = HashMap<String, Long>()

    /** The level each nick is known to hold, so a promotion is reported once. */
    private val levels = HashMap<String, Int>()

    /**
     * Set when our own nick has just left: on a server without userids that
     * only happens as the first half of a `changenick`, and the `onlineAdd`
     * right after it is us under the new name.
     */
    private var renamedFrom: String? = null

    fun adapt(frame: Inbound): List<Inbound> = when (frame) {
        is Inbound.OnlineSet -> listOf(onlineSet(frame))

        is Inbound.OnlineAdd -> {
            val old = renamedFrom
            renamedFrom = null
            if (old != null && frame.userid == 0L) listOf(renamed(old, frame))
            else {
                val add = if (frame.userid != 0L) frame
                else frame.copy(userid = idOf(frame.nick), trip = realTrip(frame.trip))
                ids[add.nick] = add.userid
                listOf(add)
            }
        }

        is Inbound.OnlineRemove -> when {
            frame.userid != 0L -> listOf(frame)
            // Held back rather than passed on: leaving is not what happened,
            // and our own roster entry going away would take `isme` with it.
            me != null && frame.nick == me -> {
                renamedFrom = frame.nick
                emptyList()
            }
            else -> listOf(frame.copy(userid = idOf(frame.nick)))
        }

        is Inbound.Chat -> chat(frame)

        is Inbound.Emote ->
            listOf(if (frame.userid != 0L || frame.nick.isEmpty()) frame else frame.copy(userid = idOf(frame.nick)))

        is Inbound.Info -> listOf(info(frame))

        is Inbound.Warn -> listOf(if (frame.id != null) frame else frame.copy(id = idForWarning(frame.text)))

        else -> listOf(frame)
    }

    /**
     * Our own `changenick`, as a rename under the userid we already had. A
     * nick is the only identity such a server has, so this is the one place
     * the derived id and the nick part: keeping the id is what keeps our
     * messages ours, and our roster entry `isme`, after the change.
     */
    private fun renamed(old: String, add: Inbound.OnlineAdd): Inbound {
        val id = idOf(old)
        // Whoever takes the old nick next is somebody else.
        ids.remove(old)
        levels.remove(old)?.let { levels[add.nick] = it }
        ids[add.nick] = id
        me = add.nick
        return Inbound.UpdateUser(
            nick = add.nick,
            userid = id,
            trip = realTrip(add.trip),
            hash = add.hash,
            channel = add.channel,
            time = add.time,
        )
    }

    private fun onlineSet(frame: Inbound.OnlineSet): Inbound.OnlineSet {
        if (frame.users.isNotEmpty() || frame.nicks.isEmpty()) {
            frame.users.forEach { ids[it.nick] = it.userid }
            frame.users.firstOrNull { it.isme }?.let { me = it.nick }
            return frame
        }
        // join.js pushes the joiner's own nick last; it is the only sign of
        // which entry is us.
        val mine = frame.nicks.last()
        me = mine
        return frame.copy(
            users = frame.nicks.map { nick ->
                User(nick = nick, userid = idOf(nick), isme = nick == mine, channel = frame.channel)
                    .also { ids[nick] = it.userid }
            },
        )
    }

    /**
     * Fills in the sender, and turns `mod`/`admin` into the level v2 would
     * have sent. v1 never says anyone's level otherwise — not in `onlineSet`,
     * not in `onlineAdd` — so a chat is the first we hear of a moderator, our
     * own moderator status included. The roster is told then, with an
     * `updateUser`, so that moderation can be offered from that point on.
     */
    private fun chat(frame: Inbound.Chat): List<Inbound> {
        if (frame.userid != 0L || frame.nick.isEmpty()) return listOf(frame)
        if (frame.text.startsWith(ME)) {
            // What the 2018 web client does with it: an action, by that nick.
            return listOf(
                Inbound.Emote(
                    nick = frame.nick,
                    userid = idOf(frame.nick),
                    text = "@${frame.nick} ${frame.text.removePrefix(ME)}",
                    channel = frame.channel,
                    trip = realTrip(frame.trip),
                    time = frame.time,
                )
            )
        }
        val level = when {
            frame.level != 0 -> frame.level
            frame.admin -> Levels.ADMIN
            frame.mod -> Levels.MODERATOR
            else -> 0
        }
        val chat = frame.copy(userid = idOf(frame.nick), trip = realTrip(frame.trip), level = level)
        val known = levels[frame.nick] ?: 0
        if (level <= known || frame.nick !in ids) return listOf(chat)
        levels[frame.nick] = level
        val promoted = Inbound.UpdateUser(
            nick = chat.nick,
            userid = chat.userid,
            trip = chat.trip,
            level = level,
            channel = frame.channel,
            time = frame.time,
        )
        return listOf(promoted, chat)
    }

    private fun info(frame: Inbound.Info): Inbound = when (frame.type) {
        "emote" -> {
            val nick = frame.nick.orEmpty()
            Inbound.Emote(
                nick = nick,
                userid = idOf(nick),
                // Left as "@nick waves": a v2 emote's text says it that way
                // too, and the renderer counts on the name being in it.
                text = frame.text,
                channel = frame.channel,
                trip = realTrip(frame.trip),
                time = frame.time,
            )
        }

        "whisper" -> {
            val from = frame.from.orEmpty()
            if (from.isNotEmpty()) {
                Inbound.Whisper(
                    from = idOf(from),
                    to = myId(),
                    text = frame.text.removePrefix("$from whispered: "),
                    channel = frame.channel,
                    trip = realTrip(frame.trip),
                    time = frame.time,
                )
            } else {
                // Our own, echoed: the recipient is named only in the prose.
                WHISPERED.matchEntire(frame.text)?.let { m ->
                    Inbound.Whisper(
                        from = myId(),
                        to = idOf(m.groupValues[1]),
                        text = m.groupValues[2],
                        channel = frame.channel,
                        time = frame.time,
                    )
                } ?: frame
            }
        }

        "invite" -> invite(frame)

        // 2018 marks an invite only by the `invite` key, and only on the one
        // sent to the invitee; the inviter's copy is prose alone.
        null -> if (frame.invite != null || INVITED.containsMatchIn(frame.text)) invite(frame) else frame

        else -> frame
    }

    private fun invite(frame: Inbound.Info): Inbound {
        val destination = frame.inviteChannel ?: frame.invite
            ?: INVITED.find(frame.text)?.let { frame.text.substring(it.range.last + 1) }
            ?: return frame
        val from = frame.from ?: INVITED_BY.find(frame.text)?.groupValues?.get(1).orEmpty()
        if (from.isNotEmpty()) {
            return Inbound.Invite(
                from = idOf(from),
                to = myId(),
                inviteChannel = destination,
                channel = frame.channel,
                time = frame.time,
            )
        }
        // Our own, echoed: the invitee is named only in the prose.
        return INVITED.find(frame.text)?.let { m ->
            Inbound.Invite(
                from = myId(),
                to = idOf(m.groupValues[1]),
                inviteChannel = destination,
                channel = frame.channel,
                time = frame.time,
            )
        } ?: frame
    }

    private fun idOf(nick: String): Long = ids[nick] ?: derive(nick)

    /**
     * [idFor], unless someone already holds that id under another nick —
     * which only a rename makes possible: after ours, the old nick's id is
     * still ours, and whoever takes the nick next must not inherit it.
     */
    private fun derive(nick: String): Long {
        var id = idFor(nick)
        while (ids.any { (held, heldId) -> heldId == id && !held.equals(nick, ignoreCase = true) }) id -= 1
        return id
    }

    private fun myId(): Long = me?.let(::idOf) ?: 0L

    companion object {
        /**
         * The userid a server that sends none would have to mean: derived from
         * the nick, which is what identifies someone there. Negative, so it
         * can never be mistaken for one a server issued.
         */
        fun idFor(nick: String): Long = -1L - (nick.lowercase().hashCode().toLong() and 0x7FFF_FFFFL)

        private val WHISPERED = Regex("""You whispered to @(\S+): (.*)""", RegexOption.DOT_MATCHES_ALL)
        private val INVITED = Regex("""^You invited (\S+) to \?""")
        private val INVITED_BY = Regex("""^(\S+) invited you to \?""")

        /** How a 2018 client tells an action from a chat; see [chat]. */
        private const val ME = "/me "

        /** `onlineAdd` spells a missing trip as the string "null". */
        private fun realTrip(trip: String?): String? = trip?.takeUnless { it.isEmpty() || it == "null" }

        /**
         * The ids a 2019 server would have sent, had it sent any. Only the
         * ones the session branches on: a rate limit must resync the governor
         * and back off, and a refused join must be told from a held one.
         */
        private fun idForWarning(text: String): Int? = when {
            text.startsWith("You are being rate-limited or blocked") -> ErrorId.BLOCKED
            text.contains("too fast") || text.startsWith("You are sending too much text") -> ErrorId.RATELIMIT
            text == "Nickname taken" -> ErrorId.JOIN_NAME_TAKEN
            text.startsWith("Nickname must consist") || text == "You are not the admin, liar!" -> ErrorId.JOIN_INVALID_NICK
            else -> null
        }
    }
}
