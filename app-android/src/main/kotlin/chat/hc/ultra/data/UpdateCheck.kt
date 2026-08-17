package chat.hc.ultra.data

import android.content.Context
import chat.hc.core.update.Release
import chat.hc.core.update.Releases
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Where this app is published, and therefore where an update would come from. */
const val REPO = "MinusGix/hcultra"
const val REPO_URL = "https://github.com/$REPO"

/** What a check found. */
sealed interface UpdateStatus {
    /** Nothing newer than what is installed. */
    data object UpToDate : UpdateStatus

    data class Available(val release: Release) : UpdateStatus

    /**
     * The check did not complete. Carries a reason worth showing: "could not
     * reach GitHub" and "GitHub said no" are different problems for the user,
     * and a bare "failed" invites pressing the button again forever.
     */
    data class Failed(val reason: String) : UpdateStatus
}

/** The installed version, as Android itself reports it. */
fun installedVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull() ?: "unknown"

/**
 * Ask GitHub whether there is a newer release.
 *
 * Only ever on request. There is no periodic check, no check on launch and no
 * background job: a chat client that phones home on its own schedule is telling
 * a third party when the app is running, which is a thing about the user's day,
 * and none of that is needed to answer a question they can ask in one tap.
 *
 * Nothing is sent but the request. No identifier, no version — the comparison
 * happens here, on what GitHub already publishes to anyone.
 *
 * `HttpURLConnection` rather than a client library: this is one GET against a
 * public JSON endpoint, and the app module has no HTTP dependency of its own.
 * The socket that matters is the chat one, and it is not this.
 */
suspend fun checkForUpdate(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
    val current = installedVersion(context)
    val body = runCatching { fetch(Releases.latestUrl(REPO)) }
        .getOrElse { return@withContext UpdateStatus.Failed("Could not reach GitHub") }
        ?: return@withContext UpdateStatus.Failed("GitHub did not answer with a release")

    val release = Releases.parse(body)
        ?: return@withContext UpdateStatus.Failed("No published release to compare against")

    if (Releases.isNewer(release.tag, current)) UpdateStatus.Available(release)
    else UpdateStatus.UpToDate
}

/** The response body, or null if the server answered with anything but 200. */
private fun fetch(url: String): String? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        // GitHub asks for both; without the agent it answers 403.
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "hcultra")
        connectTimeout = 10_000
        readTimeout = 10_000
        instanceFollowRedirects = true
    }
    return try {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) null
        else connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
