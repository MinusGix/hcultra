package chat.hc.ultra.data

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * A native GET of an untrusted image URL, under the WebView's rules: https only,
 * [ImageSources] only, and every redirect re-checked against both.
 * HttpURLConnection would follow a redirect on its own, and a whitelisted host
 * redirecting elsewhere would otherwise walk straight past the list.
 *
 * Sent with the WebView's own user agent, set once by whoever first has a
 * Context: these hosts saw the WebView's before the app fetched for it, and a
 * `Dalvik/…` one is the kind of change a host can decide it dislikes.
 */
object ImageFetch {

    const val MAX_BYTES = 25L * 1024 * 1024
    private const val MAX_REDIRECTS = 3

    @Volatile
    var userAgent: String? = null

    /** Thrown for a response that answered but not with the image: worth knowing it was not the network. */
    class HttpStatus(val code: Int) : IOException("HTTP $code")

    /**
     * Opens [url], following redirects by hand, and hands [read] the connection
     * once it has answered 2xx. The connection is closed afterwards either way.
     */
    fun <T> get(url: String, read: (HttpURLConnection) -> T): T {
        var current = url
        for (hop in 0..MAX_REDIRECTS) {
            if (!ImageSources.allows(current)) throw IOException("Not an allowed image host")
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                userAgent?.let { setRequestProperty("User-Agent", it) }
                setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw HttpStatus(code)
                return read(conn)
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    /** Copies at most [MAX_BYTES], failing rather than truncating past it. */
    fun copyCapped(input: InputStream, out: OutputStream) {
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
}
