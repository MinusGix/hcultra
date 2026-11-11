package chat.hc.core.session

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServersTest {

    private fun valid(input: String): String {
        val r = Servers.normalize(input)
        assertIs<Servers.Result.Valid>(r, "expected '$input' to be accepted")
        return r.url
    }

    @Test
    fun defaultIsHackChat() {
        assertEquals("wss://hack.chat/chat-ws", Servers.DEFAULT_URL)
        assertEquals(Servers.DEFAULT_URL, valid("wss://hack.chat/chat-ws"))
    }

    /** A bare host is what people actually type; assume the usual path. */
    @Test
    fun bareHostGetsSchemeAndPath() {
        assertEquals("wss://hack.chat/chat-ws", valid("hack.chat"))
        assertEquals("wss://hack.chat/chat-ws", valid("  hack.chat  "))
        assertEquals("wss://hack.chat/chat-ws", valid("hack.chat/"))
    }

    /** Pointing at a local server is the main reason this setting exists. */
    @Test
    fun localHostWithPortIsAccepted() {
        assertEquals("wss://localhost:6060/chat-ws", valid("localhost:6060"))
        assertEquals("ws://localhost:6060/chat-ws", valid("ws://localhost:6060"))
        assertEquals("ws://192.168.1.5:8080/chat-ws", valid("http://192.168.1.5:8080"))
    }

    @Test
    fun explicitPathIsPreserved() {
        assertEquals("wss://example.com/custom-ws", valid("example.com/custom-ws"))
        assertEquals("ws://example.com/a/b", valid("ws://example.com/a/b"))
    }

    /** https maps to wss, http to ws — never the other way around. */
    @Test
    fun httpSchemesMapToWebsocketSchemes() {
        assertTrue(valid("https://example.com").startsWith("wss://"))
        assertTrue(valid("http://example.com").startsWith("ws://"))
    }

    @Test
    fun rubbishIsRejectedWithAReason() {
        for (bad in listOf("", "   ", "wss://", "wss:// spaces.com", "example.com:99999", "host:abc")) {
            val r = Servers.normalize(bad)
            assertIs<Servers.Result.Invalid>(r, "expected '$bad' to be rejected")
            assertTrue(r.reason.isNotBlank())
        }
    }

    @Test
    fun plaintextIsDetectable() {
        assertTrue(Servers.isPlaintext(valid("ws://localhost:6060")))
        assertTrue(!Servers.isPlaintext(Servers.DEFAULT_URL))
    }

    /**
     * Tokens are per-server. Trying a local server must not cost the silent
     * resume on the server you normally use — a cold rejoin is visible to the
     * whole channel as a leave/join pair.
     */
    @Test
    fun tokensDoNotLeakBetweenServers() = runTest {
        val store = InMemoryTokenStore()
        val me = Credentials(nick = "tester")
        store.save("wss://hack.chat/chat-ws", "lounge", me, "live-token")
        store.save("ws://localhost:6060/chat-ws", "lounge", me, "local-token")

        assertEquals("live-token", store.load("wss://hack.chat/chat-ws", "lounge", me))
        assertEquals("local-token", store.load("ws://localhost:6060/chat-ws", "lounge", me))

        store.clear("ws://localhost:6060/chat-ws", "lounge", me)
        assertNull(store.load("ws://localhost:6060/chat-ws", "lounge", me))
        assertEquals("live-token", store.load("wss://hack.chat/chat-ws", "lounge", me))
    }

    @Test
    fun labelIsTheHost() {
        assertEquals("hack.chat", Servers.label(Servers.DEFAULT_URL))
        assertEquals("localhost:6060", Servers.label(valid("localhost:6060")))
        assertEquals("old.example", Servers.label("ws://old.example"))
    }

    /** A link to the web client is what gets shared, and the socket is elsewhere. */
    @Test
    fun aTrollegleLinkNamesItsServerAndChannel() {
        val link = Servers.link("https://bellawhiskey.ca/trollegle/chatbox/?support")!!
        assertEquals(Servers.TROLLEGLE, link.instance)
        assertEquals("wss://bellawhiskey.ca/trollegle/chatbox-ws/", link.server)
        assertEquals("support", link.channel)
        assertNull(link.nick)
        // However much of it survived being copied.
        for (input in listOf(
            "bellawhiskey.ca/trollegle/chatbox/?support",
            "http://bellawhiskey.ca/trollegle/chatbox?support",
            " https://bellawhiskey.ca/trollegle/chatbox/index.html?support ",
        )) assertEquals("support", Servers.link(input)?.channel, input)
    }

    @Test
    fun aLinkMayNameNoChannelOrANick() {
        assertNull(Servers.link("https://bellawhiskey.ca/trollegle/chatbox/")!!.channel)
        val link = Servers.link("https://hack.chat/?programming#bob#secret")!!
        assertEquals(Servers.DEFAULT_URL, link.server)
        assertEquals("programming", link.channel)
        // The page joins as everything after the first `#`, password included.
        assertEquals("bob#secret", link.nick)
    }

    @Test
    fun onlyKnownPagesAreLinks() {
        for (input in listOf(
            "support", "?support", "https://example.com/?support",
            "https://bellawhiskey.ca/?support", "https://bellawhiskey.ca/trollegle/?support",
            "wss://bellawhiskey.ca/trollegle/chatbox-ws/", "ws://hack.chat",
            "https://hack.chat/ ?x",
        )) assertNull(Servers.link(input), input)
    }

    /** Pasting the page into "Add a server" adds the server behind it. */
    @Test
    fun aPageAddressNormalizesToItsSocket() {
        assertEquals(Servers.TROLLEGLE.url, valid("https://bellawhiskey.ca/trollegle/chatbox/?support"))
        assertEquals(Servers.TROLLEGLE.url, valid(Servers.TROLLEGLE.url))
    }

    @Test
    fun knownServersAreLabelledByName() {
        assertEquals("trollegle", Servers.label(Servers.TROLLEGLE.url))
        assertEquals("hack.chat", Servers.label(Servers.DEFAULT_URL))
        assertEquals("localhost:6060", Servers.label("ws://localhost:6060/chat-ws"))
    }

    @Test
    fun trollegleIsV1AndDefaultsToSupport() {
        assertEquals(Dialect.V1, Servers.known(Servers.TROLLEGLE.url)?.dialect)
        assertEquals("support", Servers.TROLLEGLE.defaultChannel)
        assertTrue(Servers.TROLLEGLE.clientCommands)
        assertTrue(!Servers.HACK_CHAT.clientCommands)
    }
}
