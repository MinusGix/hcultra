package chat.hc.core.session

import chat.hc.core.FakeConnection
import chat.hc.core.FakeTransport
import chat.hc.core.net.RateGovernor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Captcha- and password-protected channels, scripted after enablecaptcha.js and
 * setpassword.js: the join is answered with a challenge instead of `onlineSet`,
 * the answer is a `chat`, and a wrong answer spends the challenge.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChallengeTest {

    private val tester = Credentials(nick = "tester")

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun cmdOf(raw: String) = parse(raw)["cmd"]?.jsonPrimitive?.content
    private fun textOf(raw: String) = parse(raw)["text"]?.jsonPrimitive?.content

    private fun session(transport: FakeTransport, store: TokenStore = InMemoryTokenStore()) = ChannelSession(
        channel = "testroom",
        credentials = tester,
        url = TEST_URL,
        transport = transport,
        governor = RateGovernor(),
        tokenStore = store,
    )

    private suspend fun FakeConnection.admit(token: String = "tok") {
        serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99,"channel":"testroom"}],"channel":"testroom"}""")
        serverSends("""{"cmd":"session","restored":false,"token":"$token","channels":["testroom"]}""")
    }

    private val captcha = """{"cmd":"captcha","text":"  _   _\n | | | |","channel":"testroom"}"""
    private val passwordReq = """{"cmd":"passwordreq","channel":"testroom"}"""

    @Test
    fun captchaIsShownAndTheAnswerAdmitsUs() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(captcha)
                "chat" -> if (textOf(raw) == "AbC123") admit()
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()

        val asked = assertIs<SessionState.Challenged>(s.state.value)
        val c = assertIs<Challenge.Captcha>(asked.challenge)
        assertTrue(c.art.contains("| |"), "art was ${c.art}")
        assertTrue(!c.retry)

        s.answer("AbC123")
        advanceUntilIdle()

        val answer = parse(transport.latest.sent.last())
        assertEquals("chat", answer["cmd"]?.jsonPrimitive?.content)
        // The hook reads payload.channel like every other chat hook.
        assertEquals("testroom", answer["channel"]?.jsonPrimitive?.content)
        assertEquals(SessionState.Live(restored = false), s.state.value)
        s.stop()
    }

    /** The server spends the challenge on a wrong answer; only a fresh join gets another. */
    @Test
    fun wrongCaptchaRejoinsForAFreshOne() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(captcha)
                "chat" -> serverSends("""{"cmd":"warn","text":"Incorrect captcha","id":23,"channel":false}""")
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()
        val first = assertIs<SessionState.Challenged>(s.state.value)

        s.answer("nope")
        advanceUntilIdle()

        val second = assertIs<SessionState.Challenged>(s.state.value)
        assertTrue(second.challenge.retry, "a retry must say the last answer was wrong")
        assertTrue(second.serial != first.serial)
        assertEquals(2, transport.latest.sent.count { cmdOf(it) == "join" })
        assertEquals(1, transport.connections.size, "a wrong answer is not a reason to reconnect")
        s.stop()
    }

    /**
     * session.js runs restoreJoin before it replies, and refuses a protected
     * channel — so `channels` comes back without it. That is the cue to cold-join
     * on the same socket, and the remembered password answers for the user.
     */
    @Test
    fun reconnectIntoPasswordChannelAnswersForTheUser() = runTest {
        val store = InMemoryTokenStore()
        val transport = FakeTransport()
        var restoring = false
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> if (restoring) {
                    serverSends("""{"cmd":"warn","text":"Could not auto-rejoin ?testroom. The channel is now password protected","id":221,"channel":false}""")
                    serverSends("""{"cmd":"session","restored":true,"token":"tok-r","channels":[]}""")
                } else {
                    serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                }
                "join" -> serverSends(passwordReq)
                "chat" -> if (textOf(raw) == "hunter2") admit("tok-1")
                    else serverSends("""{"cmd":"warn","text":"Invalid password","id":205,"channel":false}""")
            }
        }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()
        assertIs<Challenge.Password>(assertIs<SessionState.Challenged>(s.state.value).challenge)
        s.answer("hunter2")
        advanceUntilIdle()
        assertEquals(SessionState.Live(restored = false), s.state.value)

        restoring = true
        transport.connections[0].kill()
        advanceUntilIdle()

        assertEquals(2, transport.connections.size)
        val sent = transport.latest.sent.map { cmdOf(it) }
        assertEquals(listOf("session", "join", "chat"), sent)
        assertEquals(SessionState.Live(restored = false), s.state.value, "should not have asked again")
        s.stop()
    }

    /** A captcha cannot be answered from memory, so a reconnect has to ask. */
    @Test
    fun reconnectIntoCaptchaChannelAsksAgain() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", tester, "tok-old") }
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> {
                    serverSends("""{"cmd":"warn","text":"Could not auto-rejoin ?testroom. The channel requires captcha verification","id":22,"channel":false}""")
                    serverSends("""{"cmd":"session","restored":true,"token":"tok-r","channels":[]}""")
                }
                "join" -> serverSends(captcha)
            }
        }
        val s = session(transport, store)
        s.start(this)
        advanceUntilIdle()

        assertIs<Challenge.Captcha>(assertIs<SessionState.Challenged>(s.state.value).challenge)
        assertEquals(1, transport.connections.size, "the join goes on the restored socket")
        s.stop()
    }

    /**
     * The real order on a successful restore: restoreJoin's onlineSet goes out
     * before the session reply. The roster must still be current when the
     * session goes live, not after a handshake timeout.
     */
    @Test
    fun restoreWithOnlineSetAheadOfSessionReply() = runTest {
        val store = InMemoryTokenStore().apply { save(TEST_URL, "testroom", tester, "tok-old") }
        val transport = FakeTransport()
        transport.onSend = { raw ->
            if (cmdOf(raw) == "session") {
                serverSends("""{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"testroom"}""")
                serverSends("""{"cmd":"session","restored":true,"token":"tok-r","channels":["testroom"]}""")
            }
        }
        val s = session(transport, store)
        val seen = mutableListOf<SessionEvent>()
        val collector = launchCollect(s, seen)
        s.start(this)
        advanceUntilIdle()

        assertEquals(SessionState.Live(restored = true), s.state.value)
        val roster = seen.indexOfFirst { it is SessionEvent.RosterChanged }
        val resumed = seen.indexOfFirst { it is SessionEvent.Resumed }
        assertTrue(roster in 0 until resumed, "roster ($roster) must be set before Resumed ($resumed)")
        assertEquals(99L, s.userid)
        collector.cancel()
        s.stop()
    }

    /** A socket that drops while the user is reading the captcha reconnects as usual. */
    @Test
    fun dropWhileChallengedReconnects() = runTest {
        val transport = FakeTransport()
        transport.onSend = { raw ->
            when (cmdOf(raw)) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(captcha)
            }
        }
        val s = session(transport)
        s.start(this)
        advanceUntilIdle()
        assertIs<SessionState.Challenged>(s.state.value)

        transport.connections[0].kill()
        advanceUntilIdle()

        assertEquals(2, transport.connections.size)
        assertIs<SessionState.Challenged>(s.state.value)
        s.stop()
    }
}
