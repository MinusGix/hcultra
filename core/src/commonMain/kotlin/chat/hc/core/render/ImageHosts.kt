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
     * Whether [url] is an image the renderer may load.
     *
     * https only. The site's list is scheme-blind, but these hosts all serve
     * https, and a cleartext fetch would announce the request — and the channel
     * you are reading it in — to every hop along the way.
     */
    fun allows(url: String): Boolean {
        val scheme = url.substringBefore("://", missingDelimiterValue = "")
        if (!scheme.equals("https", ignoreCase = true)) return false
        return host(url) in hosts
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
        val authority = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
        return authority.substringAfterLast('@')
            .substringBefore(':')
            .trimEnd('.')          // a trailing dot is the same host to DNS
            .lowercase()
    }
}
