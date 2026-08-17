package chat.hc.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {

    @Test
    fun laterVersionsAreNewer() {
        assertTrue(Releases.isNewer("0.2.0", "0.1.0"))
        assertTrue(Releases.isNewer("1.0.0", "0.9.9"))
        assertTrue(Releases.isNewer("0.1.1", "0.1.0"))
        assertTrue(Releases.isNewer("0.10.0", "0.9.0"), "compared part-wise, not as text")
    }

    @Test
    fun theSameVersionIsNotNewer() {
        assertFalse(Releases.isNewer("0.1.0", "0.1.0"))
        assertFalse(Releases.isNewer("v0.1.0", "0.1.0"), "a leading v is tag punctuation")
        assertFalse(Releases.isNewer("0.1", "0.1.0"), "a missing part is zero")
    }

    @Test
    fun olderVersionsAreNotNewer() {
        assertFalse(Releases.isNewer("0.1.0", "0.2.0"))
        assertFalse(Releases.isNewer("0.9.9", "1.0.0"))
    }

    /** A release outranks a prerelease of the same version, and not the reverse. */
    @Test
    fun prereleasesRankBelowTheirRelease() {
        assertTrue(Releases.isNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(Releases.isNewer("1.0.0-rc1", "1.0.0"))
        assertTrue(Releases.isNewer("1.0.0-rc2", "1.0.0-rc1"))
    }

    /** Unreadable means "no update", never "update available". */
    @Test
    fun nonsenseIsNeverNewer() {
        assertFalse(Releases.isNewer("", "0.1.0"))
        assertFalse(Releases.isNewer("latest", "0.1.0"))
        assertFalse(Releases.isNewer("0.2.0", "unknown"))
        assertFalse(Releases.isNewer("nightly-2026-08-17", "0.1.0"))
    }

    @Test
    fun parsesAReleasePayload() {
        val body = """
            {"tag_name":"v0.2.0",
             "html_url":"https://github.com/MinusGix/hcultra/releases/tag/v0.2.0",
             "draft":false,"prerelease":false,
             "assets":[{"name":"hcultra-0.2.0.apk"}]}
        """.trimIndent()

        assertEquals(
            Release("v0.2.0", "https://github.com/MinusGix/hcultra/releases/tag/v0.2.0"),
            Releases.parse(body),
        )
    }

    @Test
    fun refusesDraftsAndPrereleases() {
        val draft = """{"tag_name":"v9.0.0","html_url":"https://example.test/9","draft":true}"""
        val pre = """{"tag_name":"v9.0.0","html_url":"https://example.test/9","prerelease":true}"""

        assertNull(Releases.parse(draft))
        assertNull(Releases.parse(pre))
    }

    @Test
    fun refusesAnythingThatIsNotARelease() {
        assertNull(Releases.parse(""))
        assertNull(Releases.parse("not json"))
        assertNull(Releases.parse("""{"message":"API rate limit exceeded"}"""))
        assertNull(Releases.parse("""{"tag_name":"v1.0.0"}"""), "no url to send anyone to")
    }

    @Test
    fun buildsTheEndpointForARepo() {
        assertEquals(
            "https://api.github.com/repos/MinusGix/hcultra/releases/latest",
            Releases.latestUrl("MinusGix/hcultra"),
        )
    }
}
