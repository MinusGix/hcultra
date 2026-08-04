package chat.hc.core.session

import chat.hc.core.protocol.Inbound
import chat.hc.core.protocol.User

/**
 * Channel roster.
 *
 * Keyed by `userid`, never by nick. Two reasons, both observed against live:
 *  - nicks change under a stable userid (`changenick`), and
 *  - during a make-before-break handoff the server's `onlineSet` lists us
 *    **twice** — old socket and new — under one userid. Keying by nick would
 *    leave a phantom peer in the list.
 */
class PresenceTracker {
    private val users = LinkedHashMap<Long, User>()

    val roster: List<User> get() = users.values.toList()

    fun size(): Int = users.size

    operator fun get(userid: Long): User? = users[userid]

    /** Replaces the whole roster; `onlineSet` is authoritative. */
    fun reset(incoming: List<User>) {
        users.clear()
        // Deduplicate by userid, keeping the last mention (the "isme" entry for
        // our own duplicated socket carries the more complete record).
        incoming.forEach { users[it.userid] = mergeInto(users[it.userid], it) }
    }

    fun add(u: User) {
        users[u.userid] = mergeInto(users[u.userid], u)
    }

    fun remove(userid: Long): User? = users.remove(userid)

    /**
     * `updateUser` carries partial state — it is how a silent resume and a
     * privilege change both arrive — so merge rather than overwrite.
     */
    fun update(f: Inbound.UpdateUser) {
        val existing = users[f.userid]
        users[f.userid] = User(
            nick = f.nick.ifEmpty { existing?.nick ?: "" },
            userid = f.userid,
            trip = f.trip ?: existing?.trip,
            hash = f.hash ?: existing?.hash,
            level = if (f.level != 0) f.level else existing?.level ?: 0,
            color = f.color ?: existing?.color,
            flair = f.flair ?: existing?.flair,
            uType = f.uType ?: existing?.uType,
            isBot = f.isBot,
            channel = existing?.channel,
            isme = existing?.isme ?: false,
            online = f.online,
        )
    }

    private fun mergeInto(existing: User?, incoming: User): User =
        if (existing == null) incoming
        else incoming.copy(
            trip = incoming.trip ?: existing.trip,
            hash = incoming.hash ?: existing.hash,
            color = incoming.color ?: existing.color,
            flair = incoming.flair ?: existing.flair,
            uType = incoming.uType ?: existing.uType,
            channel = incoming.channel ?: existing.channel,
            isme = incoming.isme || existing.isme,
        )
}
