package chat.hc.core.session

import chat.hc.core.FakeConnection
import chat.hc.core.FakeTransport
import chat.hc.core.net.RateGovernor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
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
     * Every `in chat` hook (`/me`, `/w`, `/nick`, …) bails unless the payload
     * names its channel, and only v1 sockets get it filled in by legacylayer.
     * Plain text still works without it — chat.js falls back to the socket's
     * first channel — so omitting it fails only for commands, which land in
     * finalCmdCheck as "Unknown command".
     */
    @Test
    fun chatNamesItsChannel() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        s.sendChat("/me waves")
        val last = Json.parseToJsonElement(transport.latest.sent.last()).jsonObject
        assertEquals("chat", last["cmd"]?.jsonPrimitive?.content)
        assertEquals("testroom", last["channel"]?.jsonPrimitive?.content)
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

    /** A first join has no ghost to collide with: NAME_TAKEN there is someone else's nick. */
    @Test
    fun nameTakenOnFirstJoinIsFatal() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends("""{"cmd":"warn","text":"Nickname taken in channel: ?testroom","id":33,"channel":false}""")
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        assertTrue(s.state.value is SessionState.Failed, "expected Failed, got ${s.state.value}")
        assertEquals(1, transport.connections.size)
        s.stop()
    }

    /**
     * Scripts the server after a drop in a password channel: token restore is
     * refused (221), and the cold join collides with our own unreaped socket
     * [collisions] times before the server lets go of it.
     */
    private fun FakeTransport.scriptGhostedRejoin(collisions: Int) {
        var joins = 0
        onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> {
                    serverSends("""{"cmd":"warn","text":"Could not auto-rejoin ?testroom. The channel is now password protected","id":221,"channel":false}""")
                    serverSends("""{"cmd":"session","restored":false,"token":"tok-2","channels":[]}""")
                }
                "join" -> {
                    joins += 1
                    if (joins <= collisions) {
                        serverSends("""{"cmd":"warn","text":"Nickname taken in channel: ?testroom","id":33,"channel":false}""")
                    } else {
                        serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                    }
                }
            }
        }
    }

    /**
     * Restore is refused into password and captcha channels, and the cold join
     * that follows counts our own dead socket as a nick collision until the
     * server reaps it. That must back off and retry, not fail the tab.
     */
    @Test
    fun rejoinRetriesWhileOurGhostHoldsTheNick() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        transport.scriptGhostedRejoin(collisions = 2)
        transport.connections[0].kill()
        advanceUntilIdle()

        assertEquals(SessionState.Live(restored = false), s.state.value)
        assertEquals(4, transport.connections.size, "two refused rejoins, then admitted")
        s.stop()
    }

    /** The ghost wait is bounded: a nick still taken minutes later is someone else's. */
    @Test
    fun ghostRetriesGiveUpEventually() = runTest {
        val transport = FakeTransport().apply { scriptColdJoin() }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        transport.scriptGhostedRejoin(collisions = Int.MAX_VALUE)
        transport.connections[0].kill()
        advanceUntilIdle()

        assertTrue(s.state.value is SessionState.Failed, "expected Failed, got ${s.state.value}")
        // Sixteen minutes of backoff — enough to outlast the server's TCP
        // noticing a dead phone (~15½ min). With the default Backoff that is
        // waits of 1.5, 3, 6, 12, 24 s then 21 at the 45 s cap: 26 refused
        // rejoins after the first socket, then the one that gives up. Not
        // asserted on currentTime: RateGovernor decays by the wall clock, so
        // under virtual time its pacing inflates the total.
        assertEquals(28, transport.connections.size)
        s.stop()
    }

    /**
     * A lock turns a join away without a warn: the server re-joins the socket
     * to ?purgatory under a random nick, and that onlineSet is all we get.
     * It must not be taken as admission, and it must not be retried.
     */
    @Test
    fun purgatoryIsALockNotAnAdmission() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"x7k2q9a1b3c4","userid":99,"channel":"purgatory"}],"channel":"purgatory"}""")
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        val state = s.state.value
        assertTrue(state is SessionState.Failed, "expected Failed, got $state")
        assertTrue("locked" in state.reason, state.reason)
        assertEquals(1, transport.connections.size, "must not have retried")
        assertTrue(s.roster.isEmpty(), "purgatory's roster leaked in: ${s.roster}")
        s.stop()
    }

    /**
     * join.js reports its rate limit as Global.RATELIMIT (11), not from the Join
     * block. It must end the handshake at once and back off — not sit out the
     * handshake timeout — and still be shown.
     */
    @Test
    fun rateLimitedJoinBacksOffAndRetries() = runTest {
        var joins = 0
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    joins += 1
                    if (joins == 1) {
                        serverSends("""{"cmd":"warn","text":"Issuing commands too quickly. Wait a moment before trying again","id":11,"channel":false}""")
                    } else {
                        serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                    }
                }
            }
        }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertEquals(SessionState.Live(restored = false), s.state.value)
        assertEquals(2, transport.connections.size)
        // One backoff plus the post-join token grace; waiting out the 10 s
        // handshake timeout first would put this well past it.
        assertTrue(currentTime < 10_000, "retried only after ${currentTime}ms")
        assertTrue(
            seen.any { it is SessionEvent.Warning && it.frame.id == 11 },
            "the rate-limit warn was swallowed",
        )
        collector.cancel()
        s.stop()
    }

    /**
     * An address over the threshold has *every* frame refused with warn
     * 987654323 instead — the `session` reply included. That must back off at
     * once, not wait out the handshake timeout for a reply that is not coming.
     */
    @Test
    fun blockedAddressBacksOffFromTheFirstFrame() = runTest {
        var sessions = 0
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> {
                    sessions += 1
                    if (sessions == 1) {
                        serverSends("""{"cmd":"warn","text":"You are being rate-limited or blocked.","id":987654323,"channel":false}""")
                    } else {
                        serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                    }
                }
                "join" -> serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
            }
        }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertEquals(SessionState.Live(restored = false), s.state.value)
        assertEquals(2, transport.connections.size)
        assertTrue(currentTime < 10_000, "retried only after ${currentTime}ms")
        assertTrue(seen.any { it is SessionEvent.Warning && it.frame.id == 987654323 }, "the block was swallowed")
        collector.cancel()
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
     * A `forceflair` reaches peers only as `updateUser`; the chat frames that
     * follow still carry the level default (`false` for an ordinary user). The
     * message must show the forced flair, not lose it to the frame.
     */
    @Test
    fun chatTakesFlairFromTheRoster() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99},{"nick":"peer","userid":7,"flair":false},{"nick":"amod","userid":8,"level":999999,"flair":"⭐"}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"updateUser","nick":"peer","userid":7,"level":100,"flair":"🦊🦊","online":true,"channel":"testroom"}""")
                    serverSends("""{"cmd":"chat","nick":"peer","userid":7,"level":100,"flair":false,"text":"hi","channel":"testroom"}""")
                    serverSends("""{"cmd":"chat","nick":"amod","userid":8,"level":999999,"flair":"⭐","text":"hi","channel":"testroom"}""")
                    serverSends("""{"cmd":"chat","nick":"ghost","userid":5,"level":9999,"flair":"💫","text":"hi","channel":"testroom"}""")
                }
            }
        }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        val flairs = seen.filterIsInstance<SessionEvent.Message>().associate { it.frame.nick to it.frame.flair }
        assertEquals("🦊🦊", flairs["peer"])
        assertEquals("⭐", flairs["amod"])
        // Not in the roster (yet): the frame's own flair is all there is.
        assertEquals("💫", flairs["ghost"])
        collector.cancel()
        s.stop()
    }

    /**
     * Losing a flair — a cleared `forceflair`, or a demotion — is an
     * `updateUser` saying `flair: false`. It must not decode to "unchanged" and
     * leave the old one in the roster, where every later message would pick it up.
     */
    @Test
    fun clearedFlairIsDropped() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> {
                    serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99},{"nick":"peer","userid":7,"flair":false},{"nick":"cmod","userid":8,"level":9999,"flair":"💫"}],"channel":"testroom"}""")
                    serverSends("""{"cmd":"updateUser","nick":"peer","userid":7,"level":100,"flair":"🦊","online":true,"channel":"testroom"}""")
                    serverSends("""{"cmd":"updateUser","nick":"peer","userid":7,"level":100,"flair":false,"online":true,"channel":"testroom"}""")
                    serverSends("""{"cmd":"updateUser","nick":"cmod","userid":8,"level":100,"flair":false,"online":true,"channel":"testroom"}""")
                    serverSends("""{"cmd":"chat","nick":"peer","userid":7,"level":100,"flair":false,"text":"hi","channel":"testroom"}""")
                    serverSends("""{"cmd":"chat","nick":"cmod","userid":8,"level":100,"flair":false,"text":"hi","channel":"testroom"}""")
                }
            }
        }
        val s = session(transport)
        val seen = mutableListOf<SessionEvent>()
        val collector = this.launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertTrue(s.roster.none { it.flair != null }, "roster still has ${s.roster.map { it.flair }}")
        val flairs = seen.filterIsInstance<SessionEvent.Message>().map { it.frame.flair }
        assertEquals(listOf<String?>(null, null), flairs)
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

internal fun CoroutineScope.launchCollect(
    s: ChannelSession,
    into: MutableList<SessionEvent>,
): Job = launch { s.events.collect { into += it } }
