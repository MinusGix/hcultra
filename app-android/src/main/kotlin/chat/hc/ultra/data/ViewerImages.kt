package chat.hc.ultra.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import chat.hc.core.render.ImageHosts
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** An image the viewer has fetched for copying, saving or sharing. */
class ViewerImage(val file: File, val mime: String, val displayName: String)

/**
 * Getting an image the viewer is showing into a form the rest of the phone can
 * take: a file in the cache, exposed through a [FileProvider].
 *
 * The viewer's WebView already has the image, but in a cache the app cannot
 * read — so this fetches it again, and only when asked. Viewing costs nothing
 * extra; copying or saving costs one request, to the host that served the
 * picture in the first place.
 *
 * The fetch keeps the WebView's rules, because it is the same untrusted URL:
 * https only, [ImageHosts] only, and every redirect re-checked against both.
 * HttpURLConnection would follow a redirect on its own, and a whitelisted host
 * redirecting elsewhere would otherwise walk straight past the list.
 */
object ViewerImages {

    private const val MAX_BYTES = 25L * 1024 * 1024
    private const val MAX_REDIRECTS = 3
    private const val DIR = "images"
    private const val KEEP_MS = 24L * 60 * 60 * 1000

    /** Fetches [url], or reuses the copy fetched earlier today. */
    suspend fun fetch(context: Context, url: String): ViewerImage = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        prune(dir)

        // Copy, then save: the second should not fetch the picture again.
        val key = hash(url)
        dir.listFiles()?.firstOrNull { it.name.startsWith("$key.") && !it.name.endsWith(".part") }?.let { held ->
            val ext = held.extension
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            if (mime != null) return@withContext ViewerImage(held, mime, displayName(url, ext))
        }

        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            if (!ImageHosts.allows(current)) throw IOException("Not an allowed image host")
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("HTTP $code")

                val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                    ?.takeIf { it.startsWith("image/") }
                    ?: throw IOException("Not an image")
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "img"
                val file = File(dir, "$key.$ext")
                val partial = File(dir, "${file.name}.part")
                conn.inputStream.use { input ->
                    partial.outputStream().use { out -> copyCapped(input, out) }
                }
                if (!partial.renameTo(file)) throw IOException("Could not store the image")
                return@withContext ViewerImage(file, mime, displayName(url, ext))
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /** A `content://` URI other apps can be granted, for the clipboard or a share. */
    fun contentUri(context: Context, image: ViewerImage): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.images", image.file)

    /**
     * Saves into `Pictures/hcultra` through MediaStore, which needs no
     * permission from Android 10 on. Older versions go through the system's
     * save dialog instead (see the viewer), since MediaStore there needs
     * storage permission this app has no other use for.
     */
    fun saveToPictures(context: Context, image: ViewerImage) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, image.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, image.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/hcultra")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("MediaStore refused the image")
        try {
            writeTo(context, image, uri)
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    /** Copies the image into a destination the user picked. */
    fun writeTo(context: Context, image: ViewerImage, uri: Uri) {
        val out = context.contentResolver.openOutputStream(uri) ?: throw IOException("Could not open $uri")
        out.use { o -> image.file.inputStream().use { it.copyTo(o) } }
    }

    private fun copyCapped(input: java.io.InputStream, out: OutputStream) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw IOException("Image is larger than 25 MB")
            out.write(buf, 0, n)
        }
    }

    /**
     * The file's own name where the URL has a sensible one — imgur's `aBc123.png`
     * is worth keeping — and a dated one otherwise. Restricted to a plain
     * character set: it is a stranger's URL, becoming a filename.
     */
    private fun displayName(url: String, ext: String): String {
        val last = Uri.parse(url).lastPathSegment.orEmpty()
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9_-]"), "")
            .take(64)
        val stem = last.ifEmpty { "hcultra-${System.currentTimeMillis()}" }
        return "$stem.$ext"
    }

    /** Cached copies live a day: long enough for a paste, short enough not to pile up. */
    private fun prune(dir: File) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }
}
