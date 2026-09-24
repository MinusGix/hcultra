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

    @Test
    fun bridgeCallIsALiteralBoolean() {
        assertEquals("HC.setAllowImages(true);", RendererBridge.allowImagesCall(true))
        assertEquals("HC.setAllowImages(false);", RendererBridge.allowImagesCall(false))
    }
}
