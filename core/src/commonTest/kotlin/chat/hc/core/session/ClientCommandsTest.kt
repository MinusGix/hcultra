package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.protocol.Outbound
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The trollegle chatbox's client-side commands; see [ClientCommands]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientCommandsTest {

    private fun parse(text: String) = ClientCommands.parse(text, "support")

    @Test
    fun commandsTheServerKnowsAreSentUnderItsNames() {
        assertEquals(ClientCommands.Action.Send(Outbound.ChangeNick("support", "bob")), parse("/nick bob"))
        assertEquals(ClientCommands.Action.Send(Outbound.Kick(channel = "support", nick = "eve")), parse("/kick eve"))
        assertEquals(ClientCommands.Action.Send(Outbound.Ban(channel = "support", nick = "eve")), parse("/ban eve "))
    }

    @Test
    fun ignoringIsLocal() {
        assertEquals(ClientCommands.Action.Ignore("eve"), parse("/ignore eve"))
        assertEquals(ClientCommands.Action.Unignore("eve"), parse("/unignore eve"))
        assertEquals(ClientCommands.Action.ShowIgnored, parse("/ignorelist"))
        assertEquals(ClientCommands.Action.ClearIgnored, parse("/clearignore"))
        assertEquals(ClientCommands.Action.IgnoreExpression("buy now", firstOnly = false), parse("/ignoreexpr buy now"))
        assertEquals(ClientCommands.Action.IgnoreExpression("^hi$", firstOnly = true), parse("/ignoreexprfirst ^hi$"))
        assertIs<ClientCommands.Action.Notice>(parse("/ignoreexpr ("))
    }

    @Test
    fun helpListsWhatTheSiteLists() {
        val help = assertIs<ClientCommands.Action.Notice>(parse("/help"))
        assertEquals(
            "Commands: /help /nick /ignore /ignoreexpr /ignoreexprfirst /unignore /ignorelist /clearignore /ban /kick",
            help.text,
        )
    }

    /** Anything else goes out as typed, as the site sends it. */
    @Test
    fun everythingElseIsAChat() {
        for (text in listOf("/me waves", "hello", "/shrug", "/nickname", " /nick bob", "//nick")) {
            assertNull(parse(text), text)
        }
    }

    /** The server has no whispers; posting one to the channel is the opposite of what was meant. */
    @Test
    fun aWhisperIsNeverPostedToTheChannel() {
        for (text in listOf("/w bob hi", "/whisper bob hi", "/r hi", "/reply hi")) {
            assertIs<ClientCommands.Action.Notice>(parse(text), text)
        }
    }

    @Test
    fun aMissingArgumentIsExplainedRatherThanSent() {
        assertIs<ClientCommands.Action.Notice>(parse("/nick"))
        assertIs<ClientCommands.Action.Notice>(parse("/kick  "))
    }

    @Test
    fun theSitesSpamFilterHidesOnlyAFirstMessage() {
        val f = ClientCommands.Filters()
        val wall = (1..60).joinToString(" ") { "word$it" }
        assertTrue(f.hides("support", "spammer", wall))
        // Still a first message: a hidden one does not count as heard.
        assertTrue(f.hides("support", "spammer", wall))
        assertFalse(f.hides("support", "regular", "hello"))
        assertFalse(f.hides("support", "regular", wall))
        // Per channel.
        assertTrue(f.hides("elsewhere", "regular", wall))
        f.add("buy now", firstOnly = false)
        assertTrue(f.hides("support", "regular", "please buy now"))
    }

    private val trollegle = Servers.TROLLEGLE.url

    /** The 2018 server, as captured from c066818: echoes every chat verbatim. */
    private fun FakeTransport.script2018(sent: MutableList<String>) {
        var nick = "tester"
        onSend = { raw ->
            sent += raw
            val f = Json.parseToJsonElement(raw).jsonObject
            when (f["cmd"]?.jsonPrimitive?.content) {
                "join" -> serverSends("""{"cmd":"onlineSet","nicks":["bob","tester"],"time":1}""")
                "chat" -> serverSends(
                    """{"cmd":"chat","nick":"$nick","text":${f["text"]},"time":2}"""
                )
                "changenick" -> {
                    val old = nick
                    nick = f["nick"]!!.jsonPrimitive.content
                    serverSends("""{"cmd":"onlineRemove","nick":"$old","time":3}""")
                    serverSends("""{"cmd":"onlineAdd","nick":"$nick","trip":"null","hash":"h","time":3}""")
                    serverSends("""{"cmd":"info","text":"$old is now $nick","time":3}""")
                }
            }
        }
    }

    private fun TestScope.manager(transport: FakeTransport) = SessionManager(
        scope = this,
        transport = transport,
        now = { 0L },
        customIdFactory = { "abc123" },
        // Everything here is 2018, whatever its address.
        dialectFor = { Dialect.V1 },
    )

    @Test
    fun onTrollegleCommandsRunHereAndNothingLeaksIntoTheChannel() = runTest {
        val sent = mutableListOf<String>()
        val transport = FakeTransport().apply { script2018(sent) }
        val sessions = manager(transport)
        val key = ChannelKey(trollegle, "support")
        sessions.join(key, Credentials("tester"))
        advanceUntilIdle()

        sessions.sendChat(key, "/ignore eve")
        sessions.sendChat(key, "/w bob psst")
        sessions.sendChat(key, "/help")
        advanceUntilIdle()

        assertTrue("eve" in sessions.ignored.value.nicks)
        assertTrue(sent.none { "\"chat\"" in it }, "nothing was said: $sent")
        val lines = sessions.channels.value.getValue(key).messages.map { it.text }
        assertTrue(lines.any { it.startsWith("Ignored: eve") }, lines.toString())
        assertTrue(lines.any { it.startsWith("This server has no whispers") }, lines.toString())
        assertTrue(lines.any { it.startsWith("Commands: /help") }, lines.toString())
        sessions.stopAll()
    }

    @Test
    fun onTrollegleNickRenamesUsAndWeStayUs() = runTest {
        val sent = mutableListOf<String>()
        val transport = FakeTransport().apply { script2018(sent) }
        val sessions = manager(transport)
        val key = ChannelKey(trollegle, "support")
        sessions.join(key, Credentials("tester"))
        advanceUntilIdle()

        sessions.sendChat(key, "/nick tester2")
        advanceUntilIdle()
        assertTrue(sent.any { "\"changenick\"" in it && "\"tester2\"" in it }, sent.toString())
        val me = sessions.me(key)
        assertEquals("tester2", me?.nick)
        assertEquals(listOf("bob", "tester2"), sessions.channels.value.getValue(key).roster.map { it.nick })

        sessions.sendChat(key, "/me waves")
        advanceUntilIdle()
        val emote = sessions.channels.value.getValue(key).messages.last()
        assertEquals("@tester2 waves", emote.text)
        assertTrue(emote.isMine)
        sessions.stopAll()
    }

    /** hack.chat parses its own commands; there they go to the server untouched. */
    @Test
    fun elsewhereCommandsAreTheServers() = runTest {
        val sent = mutableListOf<String>()
        val transport = FakeTransport().apply { script2018(sent) }
        val sessions = manager(transport)
        val key = ChannelKey("ws://old.example:6060/chat-ws", "support")
        sessions.join(key, Credentials("tester"))
        advanceUntilIdle()
        sessions.sendChat(key, "/ignore eve")
        advanceUntilIdle()
        assertTrue(sent.any { "\"chat\"" in it && "/ignore eve" in it }, sent.toString())
        assertTrue("eve" !in sessions.ignored.value.nicks)
        sessions.stopAll()
    }
}
