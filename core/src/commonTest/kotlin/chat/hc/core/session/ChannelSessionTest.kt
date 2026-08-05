package chat.hc.core.session

import chat.hc.core.FakeConnection
import chat.hc.core.FakeTransport
import chat.hc.core.net.RateGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val TEST_URL = "wss://example/chat-ws"
private val TESTER = Credentials(nick = "tester")

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelSessionTest {

    private fun cmdOf(json: String) =
        Json.parseToJsonElement(json).jsonObject["cmd"]?.jsonPrimitive?.content

    private fun session(
        transport: FakeTransport,
        store: TokenStore = InMemoryTokenStore(),
        credentials: Credentials = TESTER,
    ) = ChannelSession(
        channel = "testroom",
        credentials = credentials,
        url = TEST_URL,
        transport = transport,
        governor = RateGovernor(),
        tokenStore = store,
    )

    /** Scripts a fake server doing a normal cold join, token trailing onlineSet. */
    private fun FakeTransport.scriptColdJoin(issueToken: String? = "tok-1") {
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99,"channel":"testroom"}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"info","text":"motd","id":1304,"channel":"testroom"}""")
                    if (issueToken != null) {
                        serverSends("""{"cmd":"session","restored":false,"token":"$issueToken","channels":["testroom"]}""")
                    }
                }
            }
        }
    }

    /**
     * The single most important invariant: the first frame on any socket must be
     * `session`, because that is what selects protocol v2. A `join` first would
     * permanently downgrade the socket to the legacy dialect.
     */
    @Test
    fun firstFrameIsAlwaysSession() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        val sent = transport.latest.sent
        assertTrue(sent.isNotEmpty(), "nothing was sent")
        assertEquals("session", cmdOf(sent[0]))
        assertEquals("join", cmdOf(sent[1]))
        s.stop()
    }

    /** Even with no token we must send a session frame — it is the v2 declaration. */
    @Test
    fun tokenlessSessionFrameStillSent() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        val first = Json.parseToJsonElement(transport.latest.sent[0]).jsonObject
        assertEquals("session", first["cmd"]?.jsonPrimitive?.content)
        assertTrue("token" !in first)
        s.stop()
    }

    /**
     * The post-join token arrives after onlineSet and after the MOTD. A client
     * that treats onlineSet as "done" never captures it and loses silent resume.
     */
    @Test
    fun capturesTokenThatTrailsOnlineSet() = runTest {
        val store = InMemoryTokenStore()
        val transport = FakeTransport().apply { scriptColdJoin(issueToken = "tok-trailing") }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()

        assertEquals("tok-trailing", store.load(TEST_URL, "testroom", TESTER))
        s.stop()
    }

    /** A join that never yields a token must still reach Live, just without resume. */
    @Test
    fun missingTrailingTokenIsNotFatal() = runTest {
        val store = InMemoryTokenStore()
        val transport = FakeTransport().apply { scriptColdJoin(issueToken = null) }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()

        assertTrue(s.state.value is SessionState.Live)
        assertNull(store.load(TEST_URL, "testroom", TESTER))
        s.stop()
    }

    /** With a valid token the server restores us and no join is sent at all. */
    @Test
    fun restorePathSkipsJoin() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", TESTER, "tok-existing") }
        val transport = FakeTransport()
        transport.onSend = { raw ->
            if (cmdOf(raw) == "session") {
                serverSends("""{"cmd":"session","restored":true,"token":"tok-renewed","channels":["testroom"]}""")
                serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
            }
        }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()

        val sent = transport.latest.sent
        assertEquals(1, sent.size, "restore must not send a join")
        assertEquals("session", cmdOf(sent[0]))
        assertEquals(SessionState.Live(restored = true), s.state.value)
        // The renewed token must replace the old one, rolling the 7-day window.
        assertEquals("tok-renewed", store.load(TEST_URL, "testroom", TESTER))
        s.stop()
    }

    /** The token we hold must actually be presented on reconnect. */
    @Test
    fun presentsStoredTokenOnConnect() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", TESTER, "tok-abc") }
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()

        val first = Json.parseToJsonElement(transport.latest.sent[0]).jsonObject
        assertEquals("tok-abc", first["token"]?.jsonPrimitive?.content)
        s.stop()
    }

    /**
     * The bug this key shape exists to prevent: a restore reinstates the
     * identity the token was issued for and never reads our `join`, so a token
     * held for `tester` must not be presented when the user asked to be someone
     * else. On-device this looked like the join form ignoring the nick you typed
     * for any channel you had visited before.
     */
    @Test
    fun tokenForAnotherNickIsNotPresented() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", TESTER, "tok-tester") }
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport, store, credentials = Credentials(nick = "someoneelse"))
        s.start(this)
        advanceUntilIdle()

        val sent = transport.latest.sent
        val first = Json.parseToJsonElement(sent[0]).jsonObject
        assertTrue("token" !in first, "presented tester's token while joining as someoneelse")
        // …and therefore actually cold-joins under the nick that was asked for.
        val join = Json.parseToJsonElement(sent[1]).jsonObject
        assertEquals("join", join["cmd"]?.jsonPrimitive?.content)
        assertEquals("someoneelse", join["nick"]?.jsonPrimitive?.content)
        // tester's token is untouched, so switching back still resumes silently.
        assertEquals("tok-tester", store.load(TEST_URL, "testroom", TESTER))
        s.stop()
    }

    /**
     * The same nick with and without a trip password are different identities —
     * the trip is the whole point — so adding or dropping one must cold-join
     * rather than resume the other.
     */
    @Test
    fun tokenForTheSameNickWithoutATripIsNotPresented() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", TESTER, "tok-plain") }
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport, store, credentials = Credentials(nick = "tester", pass = "hunter2"))
        s.start(this)
        advanceUntilIdle()

        val first = Json.parseToJsonElement(transport.latest.sent[0]).jsonObject
        assertTrue("token" !in first, "resumed the tripless session for a tripped join")
        s.stop()
    }

    /** A refused join is terminal — retrying a taken nick just spends rate budget. */
    @Test
    fun refusedJoinIsFatalNotRetried() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends("""{"cmd":"warn","text":"Nickname taken","id":32,"channel":false}""")
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        assertTrue(s.state.value is SessionState.Failed, "expected Failed, got ${s.state.value}")
        assertEquals(1, transport.connections.size, "must not have reconnected")
        s.stop()
    }

    /** A dropped socket reconnects and presents the token it earned. */
    @Test
    fun reconnectsAfterDropUsingToken() = runTest {
        val store = InMemoryTokenStore()
        val transport = FakeTransport().apply { scriptColdJoin(issueToken = "tok-1") }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()
        assertEquals(1, transport.connections.size)

        // Server now honours the token, as it would on a real restore.
        transport.onSend = { raw ->
            if (cmdOf(raw) == "session") {
                serverSends("""{"cmd":"session","restored":true,"token":"tok-2","channels":["testroom"]}""")
                serverSends("""{"cmd":"onlineSet","users":[],"channel":"testroom"}""")
            }
        }
        transport.connections[0].kill()
        advanceUntilIdle()

        assertEquals(2, transport.connections.size, "should have opened a replacement socket")
        val first = Json.parseToJsonElement(transport.connections[1].sent[0]).jsonObject
        assertEquals("tok-1", first["token"]?.jsonPrimitive?.content)
        assertEquals(SessionState.Live(restored = true), s.state.value)
        s.stop()
    }

    /**
     * Chat can land between the session reply and the onlineSet. It must not be
     * dropped, and it must not jump ahead of the resume notice either — that is
     * what made a peer's message appear above "Reconnected." on-device.
     */
    @Test
    fun midHandshakeChatIsOrderedAfterTheResumeNotice() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", TESTER, "tok-existing") }
        val transport = FakeTransport()
        transport.onSend = { raw ->
            if (cmdOf(raw) == "session") {
                serverSends("""{"cmd":"session","restored":true,"token":"tok-renewed","channels":["testroom"]}""")
                // Arrives *before* the onlineSet completes the handshake.
                serverSends("""{"cmd":"chat","nick":"peer","userid":7,"text":"hi","channel":"testroom"}""")
                serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
            }
        }
        val s = session(transport, store)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        val resumed = seen.indexOfFirst { it is SessionEvent.Resumed }
        val message = seen.indexOfFirst { it is SessionEvent.Message }
        assertTrue(resumed >= 0, "no Resumed event")
        assertTrue(message >= 0, "the mid-handshake chat was dropped")
        assertTrue(resumed < message, "chat (at $message) must follow Resumed (at $resumed)")
        collector.cancel()
        s.stop()
    }

    /**
     * Deferring those frames must not become a way to lose them: a handshake
     * that fails has no resume notice to order against, so whatever arrived is
     * delivered rather than discarded.
     */
    @Test
    fun handshakeFailureStillDeliversWhatArrived() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"info","text":"that nick is reserved","id":1304,"channel":"testroom"}""")
                    serverSends("""{"cmd":"warn","text":"Nickname taken","id":32,"channel":false}""")
                }
            }
        }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertTrue(s.state.value is SessionState.Failed)
        assertTrue(
            seen.any { it is SessionEvent.Notice && it.frame.text == "that nick is reserved" },
            "the info explaining the refusal was swallowed",
        )
        collector.cancel()
        s.stop()
    }

    /** Unknown frames reach the UI layer rather than being swallowed. */
    @Test
    fun surfacesUnknownFrames() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        transport.latest.serverSends("""{"cmd":"bomb","text":"boom"}""")
        advanceUntilIdle()

        assertTrue(seen.any { it is SessionEvent.UnknownFrame && it.frame.cmd == "bomb" })
        collector.cancel()
        s.stop()
    }
}

private fun CoroutineScope.launchCollect(
    s: ChannelSession,
    into: MutableList<SessionEvent>,
): Job = launch { s.events.collect { into += it } }
