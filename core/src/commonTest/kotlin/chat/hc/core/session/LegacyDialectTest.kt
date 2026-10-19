package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.net.RateGovernor
import chat.hc.core.protocol.LegacyAdapter
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Servers that predate `session`. The fake behaves as hack.chat 2.1.91 does
 * (checked against it with probe/legacy): nothing but `join` and `chat` is
 * answered — or even acknowledged — before a socket has joined, and every
 * frame after that is the v1 shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyDialectTest {

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** A 2019 server with [others] already in the channel. */
    private fun FakeTransport.script2019(vararg others: String) {
        onSend = { raw ->
            val f = parse(raw)
            when (f.str("cmd")) {
                "join" -> {
                    val nick = f.str("nick")!!.substringBefore('#')
                    val nicks = (others.toList() + nick).joinToString(",") { "\"$it\"" }
                    serverSends("""{"cmd":"onlineSet","nicks":[$nicks],"time":1}""")
                }
                // Echoed to the whole channel, us included — with no customId.
                "chat" -> serverSends("""{"cmd":"chat","nick":"tester","text":"${f.str("text")}","time":2}""")
                // Anything else before a join is dropped without a word.
            }
        }
    }

    /** hack.chat as it is: answers `session` at once. */
    private fun FakeTransport.scriptV2(sessionDelayMillis: Long = 0) {
        onSend = { raw ->
            when (parse(raw).str("cmd")) {
                "session" -> {
                    if (sessionDelayMillis > 0) delay(sessionDelayMillis)
                    serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                }
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"session","restored":false,"token":"tok","channels":["testroom"]}""")
                }
            }
        }
    }

    private fun session(
        transport: FakeTransport,
        dialect: Dialect,
        credentials: Credentials = Credentials("tester"),
        found: MutableList<Dialect> = mutableListOf(),
        store: TokenStore = InMemoryTokenStore(),
    ) = ChannelSession(
        channel = "testroom",
        credentials = credentials,
        url = TEST_URL,
        transport = transport,
        governor = RateGovernor(),
        tokenStore = store,
        dialect = { dialect },
        onDialect = { found += it },
    )

    @Test
    fun v1JoinsFirstWithTheTripInTheNick() = runTest {
        val transport = FakeTransport().apply { script2019("bob") }
        val s = session(transport, Dialect.V1, Credentials("tester", "secret"))
        s.start(this)
        advanceUntilIdle()

        val first = parse(transport.latest.sent.first())
        assertEquals("join", first.str("cmd"))
        assertEquals("tester#secret", first.str("nick"))
        assertNull(first["pass"], "2.1.91 has no pass field")
        assertTrue(transport.latest.sent.none { parse(it).str("cmd") == "session" })
        assertTrue(s.state.value is SessionState.Live)
        assertTrue(s.legacy)
        assertEquals(LegacyAdapter.idFor("tester"), s.userid)
        assertEquals(listOf("bob", "tester"), s.roster.map { it.nick })
        s.stop()
    }

    /**
     * Auto against 2.1.91: `session` is met with silence, so after the probe
     * window `join` follows on the same socket, and the server is v1.
     */
    @Test
    fun autoFallsBackToJoinOnASilentServer() = runTest {
        val transport = FakeTransport().apply { script2019("bob") }
        val found = mutableListOf<Dialect>()
        val s = session(transport, Dialect.Auto, found = found)
        s.start(this)

        advanceTimeBy(3_999)
        runCurrent()
        assertEquals(listOf("session"), transport.latest.sent.map { parse(it).str("cmd") })

        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("session", "join"), transport.latest.sent.map { parse(it).str("cmd") })
        assertEquals(1, transport.connections.size, "the join belongs on the same socket")
        assertTrue(s.state.value is SessionState.Live, "state is ${s.state.value}")
        assertTrue(s.legacy)
        assertEquals(listOf(Dialect.V1), found)
        s.stop()
    }

    @Test
    fun autoOnHackChatIsV2AtOnce() = runTest {
        val transport = FakeTransport().apply { scriptV2() }
        val found = mutableListOf<Dialect>()
        val s = session(transport, Dialect.Auto, found = found)
        s.start(this)
        runCurrent()

        assertTrue(s.state.value is SessionState.Live)
        assertFalse(s.legacy)
        assertEquals(listOf(Dialect.V2), found)
        s.stop()
    }

    /**
     * A v2 server slower than the probe still gets `join` — harmless, since
     * it read `session` first — and is still recognised for what it is: its
     * `session` reply arrives ahead of the onlineSet.
     */
    @Test
    fun autoOnASlowV2ServerStaysV2() = runTest {
        val transport = FakeTransport().apply { scriptV2(sessionDelayMillis = 5_000) }
        val found = mutableListOf<Dialect>()
        val store = InMemoryTokenStore()
        val s = session(transport, Dialect.Auto, found = found, store = store)
        // The fake replies from inside send(), so the delay would hold up the
        // handshake itself; run it off to the side as a real server would —
        // in order, one frame after another, which is the property Auto
        // relies on.
        val original = transport.onSend
        val server = this
        val inOrder = Mutex()
        transport.onSend = { raw ->
            val conn = this
            server.launch { inOrder.withLock { original(conn, raw) } }
        }
        s.start(this)
        advanceTimeBy(10_000)
        runCurrent()

        assertTrue(s.state.value is SessionState.Live, "state is ${s.state.value}")
        assertFalse(s.legacy)
        assertEquals(listOf(Dialect.V2), found)
        assertEquals("tok", store.load(TEST_URL, "testroom", Credentials("tester")))
        s.stop()
    }

    /**
     * The trip is the identity, so Auto's fallback join carries it the v1
     * way. A slow v2 server refuses `nick#password` as a nick, and is then
     * joined the v2 way instead — one refusal, never a wrong identity.
     */
    @Test
    fun autoFallbackKeepsTheTripOnEitherServer() = runTest {
        val old = FakeTransport().apply { script2019() }
        val onOld = session(old, Dialect.Auto, Credentials("tester", "secret"))
        onOld.start(this)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("tester#secret", parse(old.latest.sent[1]).str("nick"))
        assertTrue(onOld.state.value is SessionState.Live)
        onOld.stop()

        val slow = FakeTransport()
        slow.onSend = { raw ->
            val f = parse(raw)
            when (f.str("cmd")) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> if ('#' in f.str("nick")!!) {
                    serverSends("""{"cmd":"warn","text":"Nickname must consist of up to 24 letters, numbers, and underscores","id":31,"channel":false}""")
                } else {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                }
            }
        }
        // Holds the `session` reply back past the probe window, in order.
        val reply = slow.onSend
        val server = this
        val inOrder = Mutex()
        slow.onSend = { raw ->
            val conn = this
            server.launch {
                inOrder.withLock {
                    if (parse(raw).str("cmd") == "session") delay(5_000)
                    reply(conn, raw)
                }
            }
        }
        val onSlow = session(slow, Dialect.Auto, Credentials("tester", "secret"))
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(onSlow, seen)
        onSlow.start(this)
        advanceTimeBy(12_000)
        runCurrent()

        val joins = slow.latest.sent.map(::parse).filter { it.str("cmd") == "join" }
        assertEquals(listOf("tester#secret", "tester"), joins.map { it.str("nick") })
        assertEquals("secret", joins[1].str("pass"))
        assertTrue(onSlow.state.value is SessionState.Live, "state is ${onSlow.state.value}")
        assertFalse(onSlow.legacy)
        assertTrue(seen.none { it is SessionEvent.Warning }, "the expected refusal reached the transcript")
        collector.cancel()
        onSlow.stop()
    }

    /** A v1 echo carries no customId; the session gives it back. */
    @Test
    fun ourV1EchoGetsItsCustomIdBack() = runTest {
        val transport = FakeTransport().apply { script2019() }
        val s = session(transport, Dialect.V1)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        s.sendChat("hello", customId = "abc123")
        advanceUntilIdle()

        val echo = seen.filterIsInstance<SessionEvent.Message>().single()
        assertEquals("abc123", echo.frame.customId)
        collector.cancel()
        s.stop()
    }

    /** A 2019 server that echoes chat only when [echo] says to, as it was sent. */
    private fun FakeTransport.script2019Echoing(echo: (String) -> Boolean, refuse: (String) -> Boolean = { false }) {
        onSend = { raw ->
            val f = parse(raw)
            when (f.str("cmd")) {
                "join" -> serverSends("""{"cmd":"onlineSet","nicks":["tester"],"time":1}""")
                "chat" -> {
                    val text = f.str("text")!!
                    if (refuse(text)) throw IllegalStateException("socket refused the write")
                    if (echo(text)) serverSends("""{"cmd":"chat","nick":"tester","text":"${LegacyEcho.expected(text)}","time":2}""")
                }
            }
        }
    }

    /**
     * A write that failed never reaches the server, so it can have no echo:
     * it must not stay in line to claim the next message that says the same.
     */
    @Test
    fun aRefusedWriteDoesNotClaimTheNextEcho() = runTest {
        val transport = FakeTransport()
        var writes = 0
        transport.script2019Echoing(echo = { true }, refuse = { writes++ == 0 })
        val s = session(transport, Dialect.V1)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertTrue(runCatching { s.sendChat("hello", customId = "first") }.isFailure)
        s.sendChat("hello", customId = "second")
        advanceUntilIdle()

        val echo = seen.filterIsInstance<SessionEvent.Message>().single()
        assertEquals("second", echo.frame.customId)
        collector.cancel()
        s.stop()
    }

    /**
     * Echoes come back in the order they were sent, so one that is matched
     * gives up on everything older: a message the server dropped must not
     * later claim a newer one that happens to say the same thing.
     */
    @Test
    fun aDroppedEchoIsGivenUpOnceALaterOneArrives() = runTest {
        val transport = FakeTransport()
        var aSent = 0
        transport.script2019Echoing(echo = { it != "a" || aSent++ > 0 })
        val s = session(transport, Dialect.V1)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        s.sendChat("a", customId = "id1")
        s.sendChat("b", customId = "id2")
        s.sendChat("a", customId = "id3")
        advanceUntilIdle()

        val echoes = seen.filterIsInstance<SessionEvent.Message>().map { it.frame.text to it.frame.customId }
        assertEquals(listOf("b" to "id2", "a" to "id3"), echoes)
        collector.cancel()
        s.stop()
    }

    /** Matched on what the server will say, which is not always what was typed. */
    @Test
    fun anEchoIsMatchedAsTheServerRewritesIt() = runTest {
        val transport = FakeTransport().apply { script2019Echoing(echo = { true }) }
        val s = session(transport, Dialect.V1)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        s.sendChat("//slash", customId = "abc123")
        advanceUntilIdle()

        val echo = seen.filterIsInstance<SessionEvent.Message>().single()
        assertEquals("/slash", echo.frame.text)
        assertEquals("abc123", echo.frame.customId)
        collector.cancel()
        s.stop()
    }

    /** Someone else saying what we said is theirs, not our echo. */
    @Test
    fun aPeerSayingTheSameIsNotOurEcho() = runTest {
        val transport = FakeTransport().apply { script2019Echoing(echo = { false }) }
        val s = session(transport, Dialect.V1)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        s.sendChat("hello", customId = "abc123")
        transport.latest.serverSends("""{"cmd":"onlineAdd","nick":"bob","trip":"null","time":3}""")
        transport.latest.serverSends("""{"cmd":"chat","nick":"bob","text":"hello","time":3}""")
        advanceUntilIdle()

        val chat = seen.filterIsInstance<SessionEvent.Message>().single()
        assertEquals("bob", chat.frame.nick)
        assertNull(chat.frame.customId)
        collector.cancel()
        s.stop()
    }

    @Test
    fun rateLimitedJoinIsRetriedNotFatal() = runTest {
        val transport = FakeTransport()
        var joins = 0
        transport.onSend = { raw ->
            if (parse(raw).str("cmd") == "join") {
                joins += 1
                if (joins == 1) serverSends("""{"cmd":"warn","text":"You are joining channels too fast. Wait a moment and try again."}""")
                else serverSends("""{"cmd":"onlineSet","nicks":["tester"]}""")
            }
        }
        val s = session(transport, Dialect.V1)
        s.start(this)
        advanceTimeBy(30_000)
        runCurrent()
        assertTrue(s.state.value is SessionState.Live, "state is ${s.state.value}")
        s.stop()
    }

    @Test
    fun takenNickOnAFirstJoinIsFatal() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            if (parse(raw).str("cmd") == "join") serverSends("""{"cmd":"warn","text":"Nickname taken"}""")
        }
        val s = session(transport, Dialect.V1)
        s.start(this)
        advanceUntilIdle()
        assertTrue(s.state.value is SessionState.Failed, "state is ${s.state.value}")
        s.stop()
    }

    // --- through the manager ----------------------------------------------

    private fun TestScope.manager(transport: FakeTransport, dialect: Dialect = Dialect.V1) = SessionManager(
        scope = this,
        transport = transport,
        now = { 0L },
        customIdFactory = { "abc123" },
        dialectFor = { dialect },
    )

    /** The whole v1 path end to end: our message is reconciled, not doubled. */
    @Test
    fun managerReconcilesOurV1Message() = runTest {
        val transport = FakeTransport().apply { script2019("bob") }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("tester"))
        advanceUntilIdle()

        sessions.sendChat(key("testroom"), "hello")
        advanceUntilIdle()

        val chats = sessions.channels.value.getValue(key("testroom")).messages.filter { it.kind == MessageKind.Chat }
        assertEquals(1, chats.size, "echo was added as a second message: $chats")
        assertEquals(Delivery.Sent, chats.single().delivery)
        assertTrue(chats.single().isMine)
        sessions.stopAll()
    }

    /** A 2019 whisper lands as a whisper, from the right person, alerting. */
    @Test
    fun managerReadsV1Whispers() = runTest {
        val transport = FakeTransport().apply { script2019("bob") }
        val sessions = manager(transport)
        val alerts = mutableListOf<Alert>()
        val collector = launch { sessions.alerts.collect { alerts += it } }
        sessions.join(key("testroom"), Credentials("tester"))
        advanceUntilIdle()

        transport.latest.serverSends("""{"cmd":"info","type":"whisper","from":"bob","trip":"null","text":"bob whispered: psst","time":3}""")
        advanceUntilIdle()

        val w = sessions.channels.value.getValue(key("testroom")).messages.single { it.kind == MessageKind.Whisper }
        assertEquals("bob", w.nick)
        assertEquals("psst", w.text)
        assertEquals(Alert.Kind.Whisper, alerts.single().kind)
        collector.cancel()
        sessions.stopAll()
    }

    /** v1 invite and kick are addressed by nick; a userid means nothing there. */
    @Test
    fun managerAddressesV1ByNick() = runTest {
        val transport = FakeTransport().apply { script2019("bob") }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("tester"))
        advanceUntilIdle()
        val bob = sessions.channels.value.getValue(key("testroom")).roster.first { it.nick == "bob" }

        assertTrue(sessions.invite(key("testroom"), bob))
        val invite = parse(transport.latest.sent.last())
        assertEquals("invite", invite.str("cmd"))
        assertEquals("bob", invite.str("nick"))
        assertNull(invite["userid"])

        // We are a moderator once the server says so, which on v1 is when we speak.
        transport.latest.serverSends("""{"cmd":"chat","nick":"tester","text":"hi","mod":true}""")
        advanceUntilIdle()
        assertTrue(sessions.moderate(key("testroom"), ModAction.Kick, bob))
        val kick = parse(transport.latest.sent.last())
        assertEquals("kick", kick.str("cmd"))
        assertEquals("bob", kick.str("nick"))
        assertNull(kick["userid"])
        sessions.stopAll()
    }

    /**
     * What Auto learns is the server's, not the channel's: a second channel
     * on a server already found to be v1 goes straight to `join`.
     */
    @Test
    fun detectionIsSharedAcrossTheServer() = runTest {
        val transport = FakeTransport().apply { script2019() }
        val sessions = manager(transport, Dialect.Auto)
        sessions.join(key("testroom"), Credentials("tester"))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("session", parse(transport.latest.sent.first()).str("cmd"))

        sessions.join(key("elsewhere"), Credentials("tester"))
        runCurrent()
        assertEquals("join", parse(transport.latest.sent.first()).str("cmd"))
        assertTrue(sessions.channels.value.getValue(key("elsewhere")).state is SessionState.Live)
        sessions.stopAll()
    }
}
