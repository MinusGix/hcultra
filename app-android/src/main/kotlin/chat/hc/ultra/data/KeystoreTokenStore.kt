package chat.hc.ultra.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import chat.hc.core.session.TokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session tokens encrypted with an AndroidKeyStore key.
 *
 * A token is a bearer credential: it grants our nick, trip and level for seven
 * days, so it must not sit in plain preferences. Implemented directly against
 * the Keystore rather than via androidx.security-crypto, whose
 * EncryptedSharedPreferences is deprecated.
 *
 * The key is *not* user-authentication-bound: the whole point is to reconnect
 * silently while the phone is in a pocket.
 */
class KeystoreTokenStore(context: Context) : TokenStore {

    private val prefs = context.getSharedPreferences("hc_tokens", Context.MODE_PRIVATE)

    override suspend fun load(server: String, channel: String): String? = withContext(Dispatchers.IO) {
        val stored = prefs.getString(key(server, channel), null) ?: return@withContext null
        runCatching { decrypt(stored) }.getOrElse {
            // Key invalidated (e.g. device credentials removed). Drop it and
            // fall back to a fresh join rather than failing to connect.
            prefs.edit().remove(key(server, channel)).apply()
            null
        }
    }

    override suspend fun save(server: String, channel: String, token: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key(server, channel), encrypt(token)).apply()
    }

    override suspend fun clear(server: String, channel: String) = withContext(Dispatchers.IO) {
        prefs.edit().remove(key(server, channel)).apply()
    }

    /**
     * Namespaced by server: a token only means anything to the server that
     * issued it, and keeping them apart means trying a local server does not
     * cost the silent resume on the one you normally use.
     */
    private fun key(server: String, channel: String) =
        "token_${server.replace(Regex("[^A-Za-z0-9]"), "_")}_$channel"

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray())
        // iv:body — GCM IVs must be unique per encryption, never reused.
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(body, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (ivPart, bodyPart) = stored.split(":", limit = 2)
        val iv = Base64.decode(ivPart, Base64.NO_WRAP)
        val body = Base64.decode(bodyPart, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(body))
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "hcultra_session_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
    }
}
