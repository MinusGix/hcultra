package chat.hc.core.session

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
     * What to call a server where there is no room for its URL: the host, and
     * the port if one was given. The scheme and the WebSocket path say nothing
     * a person would use to tell two servers apart.
     */
    fun label(url: String): String = url.substringAfter("://").substringBefore('/')

    /** True when the endpoint is unencrypted — worth warning about in the UI. */
    fun isPlaintext(url: String): Boolean = url.startsWith("ws://")
}
