package chat.hc.ultra.data

import android.content.Context
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Embedded images, kept on disk for as long as a message in the chat still
 * shows them — fetched natively and handed to the WebView, rather than left to
 * the WebView's own HTTP cache.
 *
 * The transcript draws a window of the chat, not all of it (see
 * `TranscriptSync`), so a reader scrolling back rebuilds rows that were cut
 * away, and every `<img>` in them is a new request. Through the HTTP cache that
 * request goes back to the host whenever the host said not to keep the
 * picture — and several say exactly that: the group's own host gives an image
 * a `max-age` of what is left of its 24 hours, after which it answers 404, and
 * Discord's links are signed to expire. So a picture that showed an hour ago
 * came back broken once its link had lapsed, and one that failed to load once,
 * on a bad connection, stayed failed: nothing retried it.
 *
 * Here what the reader has seen is kept whatever the host's headers say, for
 * as long as a message still holds it ([retain]) and within [budget] bytes,
 * least recently used going first past that. A fetch that fails for want of a
 * connection is tried again before the page is told; a failure is never kept,
 * so the next time the row is drawn asks again.
 *
 * On disk rather than in memory because pictures are big: a phone photo is a
 * megabyte or two, and a heap-sized cache held a few dozen of them, which is
 * not far to scroll. The files live in the app's private cache directory and
 * no longer than the chat they came from — the history is memory only, so
 * whatever a previous process left there belongs to nothing and is deleted
 * on start ([init]), as everything is when the service ends or images are
 * turned off ([clear]).
 *
 * Process-wide, like [ImageSources], because the transcript, the viewer and the
 * service's sweep all want the same store, and the first two read it from
 * WebView threads that are nobody's composable.
 */
object TranscriptImages {

    class Image(val file: File, val mime: String, val size: Long) {
        /** When it was stored; [retain] does not judge an entry younger than the list it was given. */
        val at: Long = System.nanoTime()
    }

    private const val DIR = "transcript-images"

    /**
     * 512 MB, or a tenth of the free space if that is less: hundreds of photos,
     * more than a day of a busy channel, without crowding a nearly full phone.
     */
    private const val MAX_BUDGET = 512L * 1024 * 1024
    private var budget = MAX_BUDGET

    private const val ATTEMPTS = 3
    private val BACKOFF_MS = longArrayOf(500, 2_000)

    /** Null until [init]. */
    @Volatile
    private var dir: File? = null

    /** Access-ordered: iteration starts at the least recently used. */
    private val held = LinkedHashMap<String, Image>(64, 0.75f, true)
    private var heldBytes = 0L

    /** So that the same picture in ten rows is fetched once, not ten times at once. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<Image>>()

    /** From `Application.onCreate`, before anything can ask for a picture. */
    fun init(context: Context) {
        val d = File(context.cacheDir, DIR)
        d.deleteRecursively()
        d.mkdirs()
        budget = minOf(MAX_BUDGET, d.usableSpace / 10)
        dir = d
    }

    /**
     * The response for an image request the gate has already allowed, from the
     * store or the network. Blocks: the WebView calls this on a thread of its
     * own for exactly that.
     */
    fun serve(url: String): WebResourceResponse {
        val stream = try {
            open(peekOrNull(url)) ?: load(url).let { image ->
                val opened = open(image) ?: throw IOException("Stored image vanished")
                // Not kept: too big for the store. Open, it no longer needs a name.
                if (peekOrNull(url) !== image) image.file.delete()
                opened
            }
        } catch (e: Exception) {
            // A status rather than an empty 200, so the page sees an error and
            // the <img> shows as broken rather than as nothing at all.
            val code = (e as? ImageFetch.HttpStatus)?.code?.takeIf { it in 400..599 } ?: 502
            return WebResourceResponse("text/plain", "utf-8", code, "Unavailable", NO_STORE, ByteArrayInputStream(ByteArray(0)))
        }
        return WebResourceResponse(stream.second, null, 200, "OK", NO_STORE, stream.first)
    }

    /**
     * An open stream on [image], or null if its file is gone — Android may empty
     * a cache directory under storage pressure — in which case the entry goes
     * too and the caller fetches again. Once open, the file can be evicted
     * without harm: an unlinked file stays readable through its descriptor.
     */
    private fun open(image: Image?): Pair<FileInputStream, String>? {
        if (image == null) return null
        return try {
            FileInputStream(image.file) to image.mime
        } catch (e: IOException) {
            forget(image)
            null
        }
    }

    /** The held copy, if there is one and its source is still allowed. */
    fun peek(url: String): Image? = if (ImageSources.allows(url)) peekOrNull(url) else null

    private fun peekOrNull(url: String): Image? = synchronized(held) { held[url] }

    private fun load(url: String): Image {
        val mine = CompletableFuture<Image>()
        val theirs = inFlight.putIfAbsent(url, mine)
        if (theirs != null) {
            try {
                return theirs.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
        try {
            val image = fetchWithRetry(url)
            keep(url, image)
            mine.complete(image)
            return image
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(url, mine)
        }
    }

    private fun fetchWithRetry(url: String): Image {
        var attempt = 0
        while (true) {
            try {
                return ImageFetch.get(url) { conn ->
                    val mime = conn.contentType?.substringBefore(';')?.trim()?.lowercase()
                        ?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"
                    write(url) { out -> conn.inputStream.use { ImageFetch.copyCapped(it, out) } }
                        .let { Image(it, mime, it.length()) }
                }
            } catch (e: IOException) {
                // A host that answered has said what it means, except when what
                // it said was "not now".
                val transient = e !is ImageFetch.HttpStatus || e.code == 408 || e.code == 429 || e.code >= 500
                if (!transient || attempt >= ATTEMPTS - 1) throw e
                Thread.sleep(BACKOFF_MS[attempt])
                attempt++
            }
        }
    }

    /**
     * Into the store's directory under a name derived from [url], through a
     * partial file so a reader never sees half a picture.
     */
    private fun write(url: String, body: (java.io.OutputStream) -> Unit): File {
        val d = dir ?: throw IOException("Image store not initialised")
        val partial = File(d, "${UUID.randomUUID()}.part")
        try {
            partial.outputStream().use(body)
            val file = File(d, hash(url))
            if (!partial.renameTo(file)) throw IOException("Could not store the image")
            return file
        } finally {
            partial.delete()
        }
    }

    private fun keep(url: String, image: Image) {
        // One picture is not allowed to push out the rest; a larger one is
        // shown and then let go (see [serve]).
        if (image.size > budget / 4) return
        synchronized(held) {
            held.put(url, image)?.let { if (it.file != image.file) it.file.delete(); heldBytes -= it.size }
            heldBytes += image.size
            val it = held.entries.iterator()
            while (heldBytes > budget && it.hasNext()) {
                val old = it.next().value
                if (old === image) continue
                heldBytes -= old.size
                old.file.delete()
                it.remove()
            }
        }
    }

    private fun forget(image: Image) = synchronized(held) {
        val it = held.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value === image) {
                heldBytes -= image.size
                it.remove()
            }
        }
    }

    /**
     * Drops every picture no message in [live] shows any more — the chat moved
     * past it, or its channel was closed. [since] is when [live] was read
     * (`System.nanoTime()`): a picture stored after that may belong to a message
     * the list was too early to include.
     */
    fun retain(live: Set<String>, since: Long) = synchronized(held) {
        val it = held.entries.iterator()
        while (it.hasNext()) {
            val (url, image) = it.next()
            if (url !in live && image.at - since < 0) {
                heldBytes -= image.size
                image.file.delete()
                it.remove()
            }
        }
    }

    /** Everything, for when images are turned off or the chat ends: nothing is shown, so nothing is kept. */
    fun clear() = synchronized(held) {
        held.values.forEach { it.file.delete() }
        held.clear()
        heldBytes = 0
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }

    /** This store is the one that knows when a picture is still wanted; the WebView keeps no second copy. */
    private val NO_STORE = mapOf("Cache-Control" to "no-store")
}
