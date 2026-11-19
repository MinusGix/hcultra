package chat.hc.core.session

import chat.hc.core.net.RateGovernor

/**
 * Server endpoint handling.
 *
 * Several servers can be in use at once — hack.chat and an older instance,
 * say, with people in common — so everything a server owns (tokens, MOTD,
 * remembered identities) is keyed by its URL.
 */
object Servers {

    const val DEFAULT_URL = "wss://hack.chat/chat-ws"

    /** Path hack.chat serves its WebSocket on; appended when a bare host is given. */
    private const val DEFAULT_PATH = "/chat-ws"

    /**
     * A server worth knowing by name: where its web client lives, and what it
     * takes to use it the way that client does.
     *
     * The web client's address is what people share — a link to a channel on
     * it — and it rarely says where the socket is: hack.chat's page is at `/`
     * and its socket at `/chat-ws`, the trollegle chatbox's page is at
     * `/trollegle/chatbox/` and its socket at `/trollegle/chatbox-ws/`. So a
     * pasted link can only be followed to a server listed here.
     */
    data class Instance(
        /** What to call it where its URL will not fit. */
        val name: String,
        /** The WebSocket endpoint. */
        val url: String,
        /** The web client, as `https://host/path/`; a channel is `?name` after it. */
        val page: String,
        /** What to speak to it. Known, so there is no reason to make Auto find out. */
        val dialect: Dialect,
        /** Where its front page sends people, filled in when the channel is left blank. */
        val defaultChannel: String? = null,
        /**
         * Whether its slash commands are the web client's to run rather than the
         * server's; see [ClientCommands]. True of servers from before the
         * server learned to parse them, which broadcast `/nick bob` as a chat.
         */
        val clientCommands: Boolean = false,
        /** Whether it has whispers at all; offering one where it has none would post it to the room. */
        val whispers: Boolean = true,
        /** Its rate limiter, where it is not the one in the code it runs; see [RateLimits]. */
        val limits: RateLimits = RateLimits(),
        /** How soon to try again after a failed connection. */
        val backoff: Backoff = Backoff(),
    )

    /**
     * A server's rate limiter, in the units of [RateGovernor.Cost]: one point
     * per frame, three more for a join, and so on.
     *
     * hack.chat's code says a threshold of 25 and a half-life of 30 s, in
     * 2018 and today, but what a server runs need not be what its code says.
     */
    data class RateLimits(
        /** Kept under: [RateGovernor] refuses to let the score reach it. */
        val ceiling: Double = 25.0,
        val halflifeMillis: Long = 30_000,
    ) {
        fun governor(): RateGovernor = RateGovernor(halflifeMillis = halflifeMillis, ceiling = ceiling)
    }

    val HACK_CHAT = Instance(
        name = "hack.chat",
        url = DEFAULT_URL,
        page = "https://hack.chat/",
        dialect = Dialect.Auto,
    )

    /**
     * Trollegle's "support group" chatbox, for when Omegle wasn't working:
     * hack.chat as of July 2018 (commit c066818, by its front page), so v1, no
     * whispers, no emotes, and every slash command handled by its own client.
     *
     * Its rate limiter is not c066818's. Measured on 2026-11-19 with no-op
     * `ping` frames (one point each there): a join and eight pings in under a
     * second were enough to be blocked, and after the block it let through 6,
     * 9 and 11 more after waiting 15, 30 and 60 s. That is a threshold near 11
     * and a half-life near 15 s; c066818 lets through 22 at first, and 8, 13 and
     * 20 after the same waits. A third join in a row is refused there, where
     * c066818 refuses the seventh. So the ceiling is 10, under the measured
     * threshold for slack, and the half-life 16 s, a little slower than
     * measured, so that our model never decays faster than the server's.
     *
     * Retries start at 2 s rather than 1: a join is over a third of its budget.
     */
    val TROLLEGLE = Instance(
        name = "trollegle",
        url = "wss://bellawhiskey.ca/trollegle/chatbox-ws/",
        page = "https://bellawhiskey.ca/trollegle/chatbox/",
        dialect = Dialect.V1,
        defaultChannel = "support",
        clientCommands = true,
        whispers = false,
        limits = RateLimits(ceiling = 10.0, halflifeMillis = 16_000),
        backoff = Backoff(baseMillis = 2_000),
    )

    val KNOWN: List<Instance> = listOf(HACK_CHAT, TROLLEGLE)

    fun known(url: String): Instance? = KNOWN.firstOrNull { it.url == url }

    /** False only for a known server without them; anywhere else, assume the usual. */
    fun hasWhispers(url: String): Boolean = known(url)?.whispers ?: true

    /** A channel on a known server, as a link to its web client names it. */
    data class Link(
        val instance: Instance,
        /** Null for a link to the front page, which names none. */
        val channel: String?,
        /** The page reads `#nick` as who to join as, password and all. */
        val nick: String? = null,
    ) {
        val server: String get() = instance.url
    }

    /**
     * Reads a link to a known server's web client: `https://hack.chat/?lounge`,
     * `bellawhiskey.ca/trollegle/chatbox/?support#bob`, and so on. Null for
     * anything else, a bare channel name included.
     *
     * The channel is everything after the `?`, undecoded, since that is what
     * the page itself joins: `location.search.replace(/^\?/, '')`.
     */
    fun link(input: String): Link? {
        val raw = input.trim()
        if (raw.isEmpty() || raw.any { it.isWhitespace() }) return null
        // A `ws://` or `wss://` address is a socket, not a page.
        val scheme = raw.substringBefore("://", "")
        if (scheme.isNotEmpty() && !scheme.equals("https", true) && !scheme.equals("http", true)) return null
        val bare = raw.substringAfter("://", raw)
        val beforeFragment = bare.substringBefore('#')
        val fragment = bare.substringAfter('#', "").takeIf(String::isNotEmpty)
        val path = beforeFragment.substringBefore('?')
        val query = beforeFragment.substringAfter('?', "")
        val instance = KNOWN.firstOrNull { pageMatches(it, path) } ?: return null
        return Link(instance, query.takeIf(String::isNotEmpty), fragment)
    }

    /** `host/path/`, with or without the trailing slash or an `index.html`. */
    private fun pageMatches(instance: Instance, path: String): Boolean {
        val page = instance.page.substringAfter("://").trimEnd('/')
        val given = path.removeSuffix("/index.html").trimEnd('/')
        return given.equals(page, ignoreCase = true)
    }

    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val reason: String) : Result
    }

    /**
     * Accepts what a person would plausibly type and turns it into a URL, or
     * explains why it cannot.
     *
     * Deliberately forgiving about scheme and path — "localhost:6060" is the
     * normal way to point at a local hack.chat server — but never silently
     * downgrades an explicit `wss://` to plaintext.
     */
    fun normalize(input: String): Result {
        val raw = input.trim()
        if (raw.isEmpty()) return Result.Invalid("Enter a server address")
        // The address people have is the page's, and the socket is elsewhere.
        link(raw)?.let { return Result.Valid(it.server) }

        val withScheme = when {
            raw.startsWith("ws://") || raw.startsWith("wss://") -> raw
            raw.startsWith("http://") -> "ws://" + raw.removePrefix("http://")
            raw.startsWith("https://") -> "wss://" + raw.removePrefix("https://")
            // A bare host is assumed secure; anyone wanting plaintext can say so.
            else -> "wss://$raw"
        }

        val afterScheme = withScheme.substringAfter("://")
        val hostPart = afterScheme.substringBefore('/')
        if (hostPart.isEmpty()) return Result.Invalid("Missing host")
        if (hostPart.contains(' ')) return Result.Invalid("Host cannot contain spaces")

        val port = hostPart.substringAfter(':', "")
        if (port.isNotEmpty() && port.toIntOrNull()?.let { it in 1..65535 } != true) {
            return Result.Invalid("Invalid port")
        }

        val hasPath = afterScheme.contains('/') && afterScheme.substringAfter('/').isNotEmpty()
        val url = if (hasPath) withScheme else withScheme.trimEnd('/') + DEFAULT_PATH
        return Result.Valid(url)
    }

    /**
     * What to call a server where there is no room for its URL: its name if it
     * is a known one, otherwise the host, and the port if one was given. The scheme and the WebSocket path say nothing
     * a person would use to tell two servers apart.
     */
    fun label(url: String): String = known(url)?.name ?: url.substringAfter("://").substringBefore('/')

    /** True when the endpoint is unencrypted — worth warning about in the UI. */
    fun isPlaintext(url: String): Boolean = url.startsWith("ws://")
}
