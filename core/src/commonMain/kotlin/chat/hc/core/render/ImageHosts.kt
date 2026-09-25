package chat.hc.core.render

/**
 * The hosts an embedded image may be fetched from.
 *
 * hack.chat's own list, from `hc/client/client.js` (`imgHostWhitelist`) — the
 * site embeds `![](…)` images only from these, and everything else stays a
 * link. Keeping to the same set matters beyond parity: a message is untrusted
 * input from a public channel, and an unrestricted `<img>` would let anyone in
 * the room point the app at a URL of their choosing and learn, from their own
 * server logs, that you are here and roughly where from.
 *
 * The renderer's JavaScript carries the same list (`assets/renderer/app.js`),
 * because it decides whether to emit an `<img>` at all. This copy is what the
 * WebView's request interceptor enforces, so a mismatch between the two fails
 * closed: the image tag is written and the request is refused.
 *
 * Beyond the site's list, the user can add sources of their own as https URL
 * prefixes ([allows]'s `extra`). Those are matched by host *and* path, so
 * adding one folder of a server does not open up the rest of it.
 */
object ImageHosts {

    val hosts: Set<String> = setOf(
        "i.imgur.com",
        "imgur.com",
        "share.lyka.pro",
        "cdn.discordapp.com",
        "i.gyazo.com",
        "i.postimg.cc",
        "i.ytimg.com",
        "i.ibb.co",
        "files.catbox.moe",
        "litter.catbox.moe",
        "gateway.irys.xyz",
    )

    /**
     * The user's extra sources on a fresh install: the image host this app's
     * own small group posts from. Removable like any other entry.
     */
    val defaultExtra: List<String> = listOf("https://minus-desktop.tail084660.ts.net/i/")

    /**
     * Whether [url] is an image the renderer may load: from one of [hosts], or
     * under one of the [extra] URL prefixes (as produced by [normalizePrefix]).
     *
     * https only. The site's list is scheme-blind, but these hosts all serve
     * https, and a cleartext fetch would announce the request — and the channel
     * you are reading it in — to every hop along the way.
     */
    fun allows(url: String, extra: Collection<String> = emptyList()): Boolean {
        if (!isHttps(url)) return false
        if (host(url) in hosts) return true
        return extra.any { underPrefix(url, it) }
    }

    /**
     * A user-typed source as a canonical prefix — `https://host/path` — or null
     * when it cannot be one.
     *
     * A missing scheme means https; any other scheme is refused rather than
     * upgraded, since someone typing `http://` may have a server that only
     * speaks that. Userinfo is refused outright (it is only ever a disguise
     * here), and the port is dropped, as it is for the site's hosts.
     */
    fun normalizePrefix(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
        if (!isHttps(withScheme)) return null
        if ('@' in authority(withScheme)) return null
        val host = host(withScheme)
        if (host.isEmpty() || !host.all { it.isLetterOrDigit() || it == '-' || it == '.' }) return null
        val path = path(withScheme)
        if (hasDotSegment(path)) return null
        return "https://$host${path.ifEmpty { "/" }}"
    }

    /**
     * Same host, and the path starts with the prefix's path — a plain string
     * prefix, so `/i/` means that folder and `/i` would also take `/images`.
     *
     * A path with `.` or `..` segments never matches: `/i/../private/x.png`
     * starts with `/i/` and names something outside it.
     */
    private fun underPrefix(url: String, prefix: String): Boolean {
        if (!isHttps(prefix) || host(url) != host(prefix)) return false
        val path = path(url).ifEmpty { "/" }
        if (hasDotSegment(path)) return false
        return path.startsWith(path(prefix).ifEmpty { "/" })
    }

    private fun isHttps(url: String): Boolean =
        url.substringBefore("://", missingDelimiterValue = "").equals("https", ignoreCase = true)

    private fun authority(url: String): String =
        url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }

    /** Everything after the authority, up to the query or fragment. */
    private fun path(url: String): String =
        url.substringAfter("://").dropWhile { it != '/' && it != '?' && it != '#' }
            .takeWhile { it != '?' && it != '#' }

    /** `.`/`..` segments, spelled plainly or percent-encoded, or a backslash some parsers treat as `/`. */
    private fun hasDotSegment(path: String): Boolean {
        if ('\\' in path) return true
        return path.split('/').any { seg ->
            val decoded = seg.replace("%2e", ".", ignoreCase = true)
            decoded == "." || decoded == ".."
        }
    }

    /**
     * The host of an absolute URL, lowercased and without userinfo or port.
     *
     * Hand-rolled rather than platform URL parsing so the rule is identical on
     * every platform this core is compiled for — and testable without one.
     * Userinfo is stripped explicitly because `https://i.imgur.com@evil.test/x`
     * has host `evil.test`, and a naive prefix check would read it the other
     * way round.
     */
    private fun host(url: String): String {
        return authority(url).substringAfterLast('@')
            .substringBefore(':')
            .trimEnd('.')          // a trailing dot is the same host to DNS
            .lowercase()
    }
}
