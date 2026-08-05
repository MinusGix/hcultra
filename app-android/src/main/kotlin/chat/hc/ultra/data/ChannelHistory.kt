package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One identity, in one channel: who you were the last time you were there.
 *
 * [pass] is the trip password and a real secret; [trip] is the short hash the
 * server derived from it and is public — it appears beside every message you
 * send. Keeping both is the point: the password is what re-earns the trip, and
 * the trip is what lets you *recognise* the identity in a list, since a password
 * cannot be shown and "alice" alone does not say which alice.
 */
data class ChannelIdentity(
    val channel: String,
    val nick: String,
    val pass: String? = null,
    val trip: String? = null,
    val lastUsed: Long = 0L,
) {
    val credentials: Credentials get() = Credentials(nick = nick, pass = pass)

    /** The join field's own `nick#password` form. */
    val fieldText: String get() = nick + (pass?.let { "#$it" } ?: "")
}

/**
 * Who you have been, where, per server.
 *
 * hack.chat has no accounts: identity is whatever nick and trip password you
 * type, and people routinely use different ones in different channels. So the
 * unit worth remembering is not "your nick" but the (channel, nick) pair — which
 * is also the shape the session token is keyed by, for the same reason.
 *
 * Keyed by server because a trip is derived from a server-side salt: the same
 * password gives a different trip elsewhere, so an identity does not carry
 * across endpoints even though the nick looks identical.
 *
 * Keystore-encrypted, on its own alias, because the trip passwords are in here.
 * The channel names and nicks are not secrets, but they sit in the same blob and
 * a list of the rooms someone frequents is not nothing either.
 */
class ChannelHistory(context: Context) {

    private val prefs = context.getSharedPreferences("hc_history", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto(KEY_ALIAS)

    /** Most recently used first — the order the join screen offers them in. */
    suspend fun recent(server: String): List<ChannelIdentity> = withContext(Dispatchers.IO) {
        read(historyKey(server)).sortedByDescending { it.lastUsed }
    }

    /**
     * Records a join, keeping whatever we already knew that this call does not
     * carry. A join knows the password but not yet the trip — that only arrives
     * with the roster — so a naive overwrite would erase the trip on every
     * reconnect and leave the list unable to tell two `alice`s apart.
     */
    suspend fun record(server: String, identity: ChannelIdentity) = withContext(Dispatchers.IO) {
        val existing = read(historyKey(server))
        val previous = existing.firstOrNull { it.matches(identity) }
        val merged = identity.copy(
            // Only while the password still matches: the trip is derived from
            // it, so carrying one across a password change would label the row
            // with someone else's trip until the roster corrected it.
            trip = identity.trip ?: previous?.takeIf { it.pass == identity.pass }?.trip,
            lastUsed = maxOf(identity.lastUsed, System.currentTimeMillis()),
        )
        write(
            historyKey(server),
            (listOf(merged) + existing.filterNot { it.matches(identity) })
                .sortedByDescending { it.lastUsed }
                .take(MAX_ENTRIES),
        )
    }

    /**
     * Fills in the trip once the server has told us what it is.
     *
     * A no-op when nothing changed: this is driven off the roster, which updates
     * on every join and part in the channel, and rewriting an encrypted blob at
     * that rate would be absurd.
     *
     * Only ever *fills in*. An absent trip is the roster saying nothing useful,
     * not an assertion that the identity has none — the untripped case is
     * already carried by having no password stored, and erasing a real trip on
     * the strength of one empty field is pure loss. Observed against a fake
     * server whose restore path left the field blank: the trip vanished from the
     * list on the first resume, taking the only way to tell two `carol`s apart
     * with it. A password change clears the trip in [record] instead, which is
     * the case where the stored one is genuinely wrong.
     */
    suspend fun observeTrip(
        server: String,
        channel: String,
        nick: String,
        trip: String?,
    ) = withContext(Dispatchers.IO) {
        if (trip.isNullOrEmpty()) return@withContext
        val existing = read(historyKey(server))
        val entry = existing.firstOrNull { it.channel == channel && it.nick == nick }
            ?: return@withContext
        if (entry.trip == trip) return@withContext
        write(historyKey(server), existing.map { if (it === entry) it.copy(trip = trip) else it })
    }

    suspend fun forget(server: String, identity: ChannelIdentity) = withContext(Dispatchers.IO) {
        write(historyKey(server), read(historyKey(server)).filterNot { it.matches(identity) })
        // Otherwise "resume last" would keep offering an identity the user just
        // asked to be rid of.
        val open = read(sessionKey(server)).filterNot { it.matches(identity) }
        write(sessionKey(server), open)
    }

    /**
     * The tabs that were open, so they can be reopened together.
     *
     * Deliberately never recorded as empty. The tabs are empty on every cold
     * start and after the last one is closed, and treating either as "the last
     * session was nothing" would destroy the very thing the button exists to
     * restore — you would have to remember to press it before quitting.
     */
    suspend fun rememberOpen(server: String, open: List<ChannelIdentity>) =
        withContext(Dispatchers.IO) {
            if (open.isEmpty()) return@withContext
            write(sessionKey(server), open)
        }

    /** Resolved against the history, so each channel comes back as who you were. */
    suspend fun lastSession(server: String): List<ChannelIdentity> = withContext(Dispatchers.IO) {
        val known = read(historyKey(server))
        read(sessionKey(server)).map { open ->
            known.firstOrNull { it.matches(open) } ?: open
        }
    }

    private fun read(key: String): List<ChannelIdentity> {
        val stored = prefs.getString(key, null) ?: return emptyList()
        val plain = runCatching { crypto.decrypt(stored) }.getOrElse {
            // Key invalidated. An empty list is a far better outcome than
            // refusing to open the join screen.
            prefs.edit().remove(key).apply()
            return emptyList()
        }
        return plain.split(RECORD).mapNotNull(::decode)
    }

    private fun write(key: String, entries: List<ChannelIdentity>) {
        if (entries.isEmpty()) {
            prefs.edit().remove(key).apply()
            return
        }
        val plain = entries.joinToString(RECORD, transform = ::encode)
        prefs.edit().putString(key, crypto.encrypt(plain)).apply()
    }

    // Hand-rolled rather than JSON: this module has no serialization plugin, and
    // the separators are control characters that no nick, channel or typed
    // password can contain.
    private fun encode(e: ChannelIdentity) = listOf(
        e.channel, e.nick, e.pass.orEmpty(), e.trip.orEmpty(), e.lastUsed.toString(),
    ).joinToString(FIELD)

    private fun decode(raw: String): ChannelIdentity? {
        val f = raw.split(FIELD)
        if (f.size < 5 || f[0].isEmpty() || f[1].isEmpty()) return null
        return ChannelIdentity(
            channel = f[0],
            nick = f[1],
            pass = f[2].takeIf(String::isNotEmpty),
            trip = f[3].takeIf(String::isNotEmpty),
            lastUsed = f[4].toLongOrNull() ?: 0L,
        )
    }

    private fun historyKey(server: String) = "history_${serverKey(server)}"

    private fun sessionKey(server: String) = "session_${serverKey(server)}"

    private companion object {
        const val KEY_ALIAS = "hcultra_channel_history"
        const val MAX_ENTRIES = 30
        const val FIELD = "\u0001"
        const val RECORD = "\u0002"
    }
}

/** Identity, for storage purposes: the channel and the nick used in it. */
private fun ChannelIdentity.matches(other: ChannelIdentity) =
    channel == other.channel && nick == other.nick
