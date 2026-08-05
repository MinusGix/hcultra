package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.Credentials
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

    override suspend fun load(server: String, channel: String, identity: Credentials): String? =
        withContext(Dispatchers.IO) {
            val stored = prefs.getString(key(server, channel, identity), null)
                ?: return@withContext null
            val plain = runCatching { crypto.decrypt(stored) }.getOrElse {
                // Key invalidated (e.g. device credentials removed). Drop it and
                // fall back to a fresh join rather than failing to connect.
                prefs.edit().remove(key(server, channel, identity)).apply()
                return@withContext null
            }
            val (pass, token) = plain.split(SEPARATOR, limit = 2).let {
                it[0].takeIf(String::isNotEmpty) to it.getOrElse(1) { "" }
            }
            // The trip is the identity, so a token earned with a password is not
            // interchangeable with one earned without — see TokenStore. The nick
            // is already in the key; only the password has to be compared here.
            if (pass != identity.pass) return@withContext null
            token.takeIf(String::isNotEmpty)
        }

    override suspend fun save(server: String, channel: String, identity: Credentials, token: String) =
        withContext(Dispatchers.IO) {
            val plain = identity.pass.orEmpty() + SEPARATOR + token
            prefs.edit().putString(key(server, channel, identity), crypto.encrypt(plain)).apply()
        }

    override suspend fun clear(server: String, channel: String, identity: Credentials) =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key(server, channel, identity)).apply()
        }

    /**
     * Namespaced by server, channel and nick.
     *
     * By server because a token only means anything to the server that issued
     * it, so trying a local server does not cost the silent resume on the one
     * you normally use. By nick because presenting a token *is* choosing an
     * identity — the restore ignores whatever `join` would have said — so one
     * slot per channel would make joining under a second nick impossible.
     *
     * The password stays out of the key: preference keys are stored in plaintext
     * XML, so it lives in the encrypted value alongside the token instead.
     */
    private fun key(server: String, channel: String, identity: Credentials) =
        "token_${serverKey(server)}_${channel}_${serverKey(identity.nick)}"

    private companion object {
        const val KEY_ALIAS = "hcultra_session_tokens"

        /** A password may contain anything; a text field cannot produce this. */
        const val SEPARATOR = "\u0000"
    }
}
