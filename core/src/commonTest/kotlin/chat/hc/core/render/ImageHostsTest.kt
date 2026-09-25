package chat.hc.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ImageHostsTest {

    @Test
    fun allowsTheSitesHosts() {
        assertTrue(ImageHosts.allows("https://i.imgur.com/abc.png"))
        assertTrue(ImageHosts.allows("https://cdn.discordapp.com/attachments/1/2/x.jpg"))
        assertTrue(ImageHosts.allows("https://i.ibb.co/x/y.png?size=large#frag"))
        assertTrue(ImageHosts.allows("https://files.catbox.moe/abc123.png"))
        assertTrue(ImageHosts.allows("https://litter.catbox.moe/abc123.gif"))
        assertTrue(ImageHosts.allows("https://gateway.irys.xyz/AbC123xyz"))
    }

    @Test
    fun rejectsEverythingElse() {
        assertFalse(ImageHosts.allows("https://example.test/abc.png"))
        assertFalse(ImageHosts.allows("https://tracker.test/i.imgur.com/abc.png"))
    }

    /** `i.imgur.com` as a *prefix* of another host must not pass. */
    @Test
    fun rejectsLookalikeHosts() {
        assertFalse(ImageHosts.allows("https://i.imgur.com.evil.test/abc.png"))
        assertFalse(ImageHosts.allows("https://notimgur.com/abc.png"))
        assertFalse(ImageHosts.allows("https://catbox.moe/abc.png"))
    }

    /** Userinfo puts the real host after the `@`; reading the front is the trap. */
    @Test
    fun rejectsUserinfoDisguisedHost() {
        assertFalse(ImageHosts.allows("https://i.imgur.com@evil.test/abc.png"))
        assertFalse(ImageHosts.allows("https://i.imgur.com:pass@evil.test/abc.png"))
    }

    @Test
    fun requiresHttps() {
        assertFalse(ImageHosts.allows("http://i.imgur.com/abc.png"))
        assertFalse(ImageHosts.allows("//i.imgur.com/abc.png"))
        assertFalse(ImageHosts.allows("javascript:alert(1)"))
        assertFalse(ImageHosts.allows("file:///android_asset/renderer/app.js"))
        assertFalse(ImageHosts.allows(""))
    }

    @Test
    fun ignoresCaseAndPortAndTrailingDot() {
        assertTrue(ImageHosts.allows("HTTPS://I.Imgur.com/abc.png"))
        assertTrue(ImageHosts.allows("https://i.imgur.com.:443/abc.png"))
    }

    private val extra = listOf("https://img.example.test/i/")

    @Test
    fun allowsUnderAnExtraPrefix() {
        assertTrue(ImageHosts.allows("https://img.example.test/i/abc.png", extra))
        assertTrue(ImageHosts.allows("https://IMG.example.test./i/abc.png?x=1#f", extra))
        assertTrue(ImageHosts.allows("https://minus-desktop.tail084660.ts.net/i/X72qk4y2Ed3HUfQ3KM-Blw.png", ImageHosts.defaultExtra))
    }

    /** A prefix opens one folder of one host, and only over https. */
    @Test
    fun extraPrefixIsHostAndPath() {
        assertFalse(ImageHosts.allows("https://img.example.test/abc.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test/private/abc.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test.evil.test/i/abc.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test@evil.test/i/abc.png", extra))
        assertFalse(ImageHosts.allows("http://img.example.test/i/abc.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test/i/abc.png"))
    }

    @Test
    fun extraPrefixRejectsDotSegments() {
        assertFalse(ImageHosts.allows("https://img.example.test/i/../private/x.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test/i/%2E%2e/private/x.png", extra))
        assertFalse(ImageHosts.allows("https://img.example.test/i/..\\private/x.png", extra))
    }

    @Test
    fun normalizesTypedPrefixes() {
        assertEquals("https://img.example.test/i/", ImageHosts.normalizePrefix("  img.example.test/i/ "))
        assertEquals("https://img.example.test/", ImageHosts.normalizePrefix("HTTPS://Img.Example.Test:8443"))
        assertEquals("https://img.example.test/i/", ImageHosts.normalizePrefix("https://img.example.test/i/?q#f"))
        assertEquals(null, ImageHosts.normalizePrefix("http://img.example.test/i/"))
        assertEquals(null, ImageHosts.normalizePrefix("https://a@img.example.test/"))
        assertEquals(null, ImageHosts.normalizePrefix("https://img.example.test/../x"))
        assertEquals(null, ImageHosts.normalizePrefix("https:///i/"))
        assertEquals(null, ImageHosts.normalizePrefix("img example"))
        assertEquals(null, ImageHosts.normalizePrefix(""))
    }

    /** A root prefix admits the whole host. */
    @Test
    fun rootPrefixAdmitsTheHost() {
        val root = listOf(ImageHosts.normalizePrefix("img.example.test")!!)
        assertTrue(ImageHosts.allows("https://img.example.test/anything.png", root))
        assertTrue(ImageHosts.allows("https://img.example.test", root))
    }

    @Test
    fun bridgeCallIsALiteralBooleanAndList() {
        assertEquals("HC.setAllowImages(true, []);", RendererBridge.allowImagesCall(true))
        assertEquals("HC.setAllowImages(false, []);", RendererBridge.allowImagesCall(false))
        assertEquals(
            "HC.setAllowImages(true, [\"https://a.test/i/\"]);",
            RendererBridge.allowImagesCall(true, listOf("https://a.test/i/")),
        )
    }
}
