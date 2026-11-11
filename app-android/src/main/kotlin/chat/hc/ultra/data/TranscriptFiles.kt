package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.session.ChannelKey
import chat.hc.core.session.SavedTranscript
import chat.hc.core.session.TranscriptStore
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.TranscriptCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Each open channel's scrollback, kept in app storage for the next process.
 *
 * One Keystore-encrypted file per channel, on its own alias. Whispers are in
 * here, and a record of who said what is exactly what hack.chat's no-logs norm
 * is about, so it is held to the same standard as the trip passwords. Named by
 * a hash of the server and channel, so the directory listing does not say
 * where you have been either.
 *
 * When each channel was last seen connected is not secret, and changes every
 * time the service checks in, so it lives in plain preferences rather than
 * costing a re-encryption of the whole transcript.
 *
 * [enabled] is the user's switch, read per call. Off, nothing is read or
 * written, and [clearAll] is how turning it off takes the files with it.
 */
class TranscriptFiles(context: Context, private val enabled: () -> Boolean) : TranscriptStore {

    private val dir = File(context.filesDir, "transcripts")
    private val prefs = context.getSharedPreferences("hc_transcripts", Context.MODE_PRIVATE)
    private val crypto = KeystoreCrypto(KEY_ALIAS)

    override suspend fun load(key: ChannelKey): SavedTranscript? = withContext(Dispatchers.IO) {
        if (!enabled()) return@withContext null
        val file = fileFor(key)
        if (!file.exists()) return@withContext null
        // A key the Keystore lost (a restored backup, a wiped lock screen) makes
        // the file unreadable for good; it reads as nothing and is replaced.
        val messages = runCatching { TranscriptCodec.decode(crypto.decrypt(file.readText())) }.getOrNull()
            ?: return@withContext null
        SavedTranscript(messages, prefs.getLong(name(key), file.lastModified()))
    }

    override suspend fun save(key: ChannelKey, messages: List<ChatMessage>, lastSeen: Long) {
        withContext(Dispatchers.IO) {
            if (!enabled()) return@withContext
            dir.mkdirs()
            // Written beside and moved over, so a process killed mid-write
            // leaves the previous copy rather than half of this one.
            val tmp = File(dir, name(key) + ".tmp")
            tmp.writeText(crypto.encrypt(TranscriptCodec.encode(messages)))
            if (!tmp.renameTo(fileFor(key))) tmp.delete()
            prefs.edit().putLong(name(key), lastSeen).apply()
        }
    }

    override suspend fun seen(key: ChannelKey, lastSeen: Long) {
        withContext(Dispatchers.IO) {
            if (!enabled() || !fileFor(key).exists()) return@withContext
            prefs.edit().putLong(name(key), lastSeen).apply()
        }
    }

    override suspend fun delete(key: ChannelKey) {
        withContext(Dispatchers.IO) {
            fileFor(key).delete()
            prefs.edit().remove(name(key)).apply()
        }
    }

    /** Every saved transcript, for the switch being turned off. */
    suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            dir.deleteRecursively()
            prefs.edit().clear().apply()
        }
    }

    private fun fileFor(key: ChannelKey) = File(dir, name(key))

    private fun name(key: ChannelKey): String =
        MessageDigest.getInstance("SHA-256")
            .digest((key.server + "\u0000" + key.channel).toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val KEY_ALIAS = "hc_transcripts"
    }
}
