package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.Credentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The nick and trip password last used, per server.
 *
 * hack.chat's own client keeps these in `localStorage` and re-logs-in from them,
 * so remembering both is parity rather than overreach — but the password is
 * exactly the secret that produces the trip, so it gets the same Keystore
 * treatment as the session tokens rather than sitting beside the theme prefs.
 *
 * Keyed by server for the same reason tokens are: a trip is derived from a
 * server-side salt, so the same password yields a different trip elsewhere and
 * the two are not interchangeable.
 *
 * This is a convenience over the token path, not a replacement for it — a token
 * resumes *the existing session* invisibly, whereas these credentials only make
 * a fresh join one tap instead of retyping.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("hc_credentials", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto(KEY_ALIAS)

    suspend fun load(server: String): Credentials? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key(server), null) ?: return@withContext null
        val plain = runCatching { crypto.decrypt(stored) }.getOrElse {
            // Key invalidated. An empty join form is a far better outcome than
            // refusing to open the screen.
            prefs.edit().remove(key(server)).apply()
            return@withContext null
        }
        // A password may legitimately contain anything, so split once on a
        // separator that a text field cannot produce.
        val parts = plain.split(SEPARATOR, limit = 2)
        val nick = parts[0]
        if (nick.isEmpty()) return@withContext null
        Credentials(nick = nick, pass = parts.getOrNull(1)?.takeIf(String::isNotEmpty))
    }

    suspend fun save(server: String, credentials: Credentials) = withContext(Dispatchers.IO) {
        val plain = credentials.nick + SEPARATOR + credentials.pass.orEmpty()
        prefs.edit().putString(key(server), crypto.encrypt(plain)).apply()
    }

    suspend fun clear(server: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(server)).apply()
    }

    private fun key(server: String) = "creds_${serverKey(server)}"

    private companion object {
        const val KEY_ALIAS = "hcultra_credentials"
        const val SEPARATOR = "\u0000"
    }
}
