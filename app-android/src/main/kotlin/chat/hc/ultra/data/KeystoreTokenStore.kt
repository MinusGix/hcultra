package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Session tokens encrypted with an AndroidKeyStore key.
 *
 * A token is a bearer credential: it grants our nick, trip and level for seven
 * days, so it must not sit in plain preferences. Implemented directly against
 * the Keystore rather than via androidx.security-crypto, whose
 * EncryptedSharedPreferences is deprecated.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.getSharedPreferences("hc_tokens", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto(KEY_ALIAS)

    override suspend fun load(server: String, channel: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key(server, channel), null) ?: return@withContext null
        runCatching { crypto.decrypt(stored) }.getOrElse {
            // Key invalidated (e.g. device credentials removed). Drop it and
            // fall back to a fresh join rather than failing to connect.
            prefs.edit().remove(key(server, channel)).apply()
            null
        }
    }

    override suspend fun save(server: String, channel: String, token: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key(server, channel), crypto.encrypt(token)).apply()
    }

    override suspend fun clear(server: String, channel: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(server, channel)).apply()
    }

    /**
     * Namespaced by server: a token only means anything to the server that
     * issued it, and keeping them apart means trying a local server does not
     * cost the silent resume on the one you normally use.
     */
    private fun key(server: String, channel: String) = "token_${serverKey(server)}_$channel"

    private companion object {
        const val KEY_ALIAS = "hcultra_session_tokens"
    }
}
