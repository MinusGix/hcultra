package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.MotdStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The server's MOTD, kept across app launches.
 *
 * Plain preferences, unlike [KeystoreTokenStore]: this is a line the server
 * broadcasts to everyone who connects, so there is nothing here that anyone
 * reading the file did not already have. It is persisted rather than held in
 * memory because a fresh launch is exactly the case it exists for — the socket
 * restores a token, the server sends no MOTD, and the in-memory copy died with
 * the last process.
 */
class MotdPrefs(context: Context) : MotdStore {

    private val prefs = context.getSharedPreferences("hc_motd", Context.MODE_PRIVATE)

    override suspend fun load(server: String): String? =
        withContext(Dispatchers.IO) { prefs.getString(server, null) }

    override suspend fun save(server: String, text: String) {
        withContext(Dispatchers.IO) { prefs.edit().putString(server, text).apply() }
    }
}
