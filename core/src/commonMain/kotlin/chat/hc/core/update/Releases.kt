package chat.hc.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A published release, as much of one as anything here needs to know. */
data class Release(
    /** The tag as published, `v` and all — shown to the user as-is. */
    val tag: String,
    /** Where to read about it, or download it. */
    val url: String,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    @SerialName("html_url") val htmlUrl: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
)

/**
 * Is there a newer build than this one, and where.
 *
 * Lives in core, away from the HTTP call that feeds it, because comparing two
 * version strings is the part that can be wrong in ways nobody notices: an app
 * that nags about an update that does not exist, or stays quiet about one that
 * does, looks the same from the outside either way. Here it is testable without
 * a network or a device.
 */
object Releases {

    private val json = Json { ignoreUnknownKeys = true }

    /** GitHub's "latest release" endpoint for a `user/repo`. */
    fun latestUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    /**
     * The release described by a GitHub API response, or null if the body is
     * not one — an error payload, a rate-limit notice, or anything unexpected.
     *
     * Drafts and prereleases are refused rather than offered. `releases/latest`
     * excludes both already; this is here so that a hand-pointed URL, or a
     * change at the other end, cannot start pushing people onto builds that
     * were deliberately not announced.
     */
    fun parse(body: String): Release? {
        val release = runCatching {
            json.decodeFromString(GithubRelease.serializer(), body)
        }.getOrNull() ?: return null
        if (release.draft || release.prerelease) return null
        if (release.tagName.isBlank() || release.htmlUrl.isBlank()) return null
        return Release(release.tagName, release.htmlUrl)
    }

    /**
     * Whether [candidate] is a later version than [current].
     *
     * Numeric dot-separated parts, compared left to right, with a missing part
     * counting as zero so `1.2` and `1.2.0` are the same version. A leading `v`
     * is tag punctuation, not part of the number.
     *
     * Anything it cannot read as a version at all is treated as *not* newer.
     * The cost of the two mistakes is not symmetric: failing to mention an
     * update is a thing the user can fix by looking, while announcing one that
     * is not there teaches them to ignore the button.
     */
    fun isNewer(candidate: String, current: String): Boolean {
        val new = parseVersion(candidate) ?: return false
        val old = parseVersion(current) ?: return false
        return compare(new, old) > 0
    }

    private data class Version(val numbers: List<Int>, val prerelease: String?)

    private fun parseVersion(raw: String): Version? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val core = trimmed.substringBefore('-').substringBefore('+')
        val prerelease = trimmed.substringAfter('-', "").substringBefore('+').ifEmpty { null }
        val numbers = core.split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.isEmpty()) return null
        return Version(numbers, prerelease)
    }

    private fun compare(a: Version, b: Version): Int {
        val width = maxOf(a.numbers.size, b.numbers.size)
        for (i in 0 until width) {
            val left = a.numbers.getOrElse(i) { 0 }
            val right = b.numbers.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        // Same numbers: a release outranks a prerelease of itself, as semver
        // says — 1.2.0 is newer than 1.2.0-rc1. Two prereleases are ordered by
        // plain string comparison, which is right for rc1/rc2 and arbitrary
        // for anything more inventive; neither of them is offered as an update
        // anyway.
        return when {
            a.prerelease == b.prerelease -> 0
            a.prerelease == null -> 1
            b.prerelease == null -> -1
            else -> a.prerelease.compareTo(b.prerelease)
        }
    }
}
