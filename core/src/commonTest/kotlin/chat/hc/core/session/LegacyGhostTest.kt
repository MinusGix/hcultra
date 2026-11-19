package chat.hc.core.session

import chat.hc.core.net.Connection
import chat.hc.core.net.RateGovernor
import chat.hc.core.net.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Our own ghost on a server with nothing to resume: the trollegle chatbox,
 * hack.chat as of July 2018. It pings every 16 s but never acts on a missing
 * pong (checked against the live server: a socket that answered none of nine
 * pings was still in the channel after 146 s), so a socket that died without
 * a goodbye keeps its nick until TCP gives up on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyGhostTest {

    /**
     * A 2018 server for one channel, with the part the session fakes leave
     * out: which sockets hold which nicks, across connections. A socket that
     * says goodbye ([Conn.close]) leaves at once; one that is [Conn.kill]ed
     * stays until [reap].
     */
    private class Server2018 : Transport {
        val conns = mutableListOf<Conn>()
        val joins = mutableListOf<String>()
        val changenicks = mutableListOf<String>()
        var beforeJoin: suspend Server2018.(String) -> Unit = {}

        override suspend fun open(url: String): Connection = Conn(this).also { conns += it }

        val present get() = conns.filter { it.nick != null }

        suspend fun leave(c: Conn) {
            val nick = c.nick ?: return
            c.nick = null
            present.forEach { it.push("""{"cmd":"onlineRemove","nick":"$nick"}""") }
        }

        /** The server finally noticing a dead socket. */
        suspend fun reap(c: Conn) = leave(c)

        suspend fun handle(c: Conn, raw: String) {
            val f = Json.parseToJsonElement(raw).jsonObject
            fun str(k: String) = f[k]?.jsonPrimitive?.content
            when (str("cmd")) {
                "join" -> {
                    if (c.nick != null) return
                    val nick = str("nick")!!.substringBefore('#')
                    joins += nick
                    beforeJoin(nick)
                    if (present.any { it.nick.equals(nick, ignoreCase = true) }) {
                        c.push("""{"cmd":"warn","text":"Nickname taken"}""")
                        return
                    }
                    present.forEach { it.push("""{"cmd":"onlineAdd","nick":"$nick","trip":"null","hash":"h"}""") }
                    c.nick = nick
                    val nicks = present.filter { it !== c }.map { it.nick!! } + nick
                    c.push("""{"cmd":"onlineSet","nicks":[${nicks.joinToString(",") { "\"$it\"" }}]}""")
                }
                "changenick" -> {
                    val new = str("nick")!!
                    changenicks += new
                    if (present.any { it.nick.equals(new, ignoreCase = true) }) {
                        c.push("""{"cmd":"warn","text":"Nickname taken"}""")
                        return
                    }
                    val old = c.nick!!
                    present.forEach { it.push("""{"cmd":"onlineRemove","nick":"$old"}""") }
                    present.forEach { it.push("""{"cmd":"onlineAdd","nick":"$new","trip":"null","hash":"h"}""") }
                    present.forEach { it.push("""{"cmd":"info","text":"$old is now $new"}""") }
                    c.nick = new
                }
            }
        }
    }

    private class Conn(private val server: Server2018) : Connection {
        private val inbox = Channel<String>(Channel.UNLIMITED)
        var nick: String? = null
        var open = true
        var saidGoodbye = false

        override val incoming: Flow<String> = inbox.consumeAsFlow()
        override val isOpen get() = open

        override suspend fun send(text: String) = server.handle(this, text)

        override suspend fun close() {
            if (!open) return
            open = false
            saidGoodbye = true
            server.leave(this)
            inbox.close()
        }

        /** The path died: we see the socket close, the server does not. */
        fun kill() {
            open = false
            inbox.close()
        }

        suspend fun push(json: String) {
            if (open) inbox.send(json)
        }
    }

    private fun session(server: Server2018, mayHaveGhost: Boolean = false, nick: String = "tester") = ChannelSession(
        channel = "testroom",
        credentials = Credentials(nick),
        url = "wss://example.invalid/chat-ws",
        transport = server,
        governor = RateGovernor(),
        dialect = { Dialect.V1 },
        mayHaveGhost = mayHaveGhost,
    )

    private fun TestScope.collect(s: ChannelSession, into: MutableList<SessionEvent>): Job =
        launch { s.events.collect { into += it } }

    private fun ChannelSession.me() = roster.firstOrNull { it.isme }?.nick

    @Test
    fun aDroppedSocketsGhostIsWaitedOutUnderAStandIn() = runTest {
        val server = Server2018()
        val s = session(server)
        val seen = mutableListOf<SessionEvent>()
        val collector = collect(s, seen)
        s.start(this)
        advanceUntilIdle()
        assertEquals("tester", s.me())

        val first = server.conns.single()
        first.kill()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(s.state.value is SessionState.Live, "state is ${s.state.value}")
        assertEquals("tester_", s.me(), "should be in under a stand-in, not locked out")
        assertEquals(listOf("tester", "tester", "tester_"), server.joins)
        assertTrue(
            seen.any { it is SessionEvent.Notice && it.frame.text.contains("renamed back to tester") },
            "the stand-in went unexplained",
        )
        assertTrue(server.changenicks.isEmpty(), "asked for the nick while the ghost still holds it")

        // The server finally notices.
        server.reap(first)
        advanceUntilIdle()

        assertEquals(listOf("tester"), server.changenicks)
        assertEquals("tester", s.me())
        assertEquals(listOf("tester"), s.roster.map { it.nick }, "the ghost or the stand-in lingers in the roster")
        collector.cancel()
        s.stop()
    }

    /** It left between our join and the stand-in's: no reason to wait for a leave we already missed. */
    @Test
    fun aGhostAlreadyGoneIsNotWaitedFor() = runTest {
        val server = Server2018()
        val s = session(server)
        s.start(this)
        advanceUntilIdle()
        val first = server.conns.single()
        first.kill()
        server.beforeJoin = { nick -> if (nick == "tester_") reap(first) }
        advanceTimeBy(5_000)
        runCurrent()
        advanceUntilIdle()
        assertEquals(listOf("tester"), server.changenicks)
        assertEquals("tester", s.me())
        s.stop()
    }

    /** After a restart nothing in this process was ever live, but the transcript says we were here a moment ago. */
    @Test
    fun aFirstJoinRightAfterARestartTreatsTheClashAsOurGhost() = runTest {
        val server = Server2018()
        val before = session(server)
        before.start(this)
        advanceUntilIdle()
        server.conns.single().kill()
        before.stop()

        val after = session(server, mayHaveGhost = true)
        after.start(this)
        advanceUntilIdle()
        assertTrue(after.state.value is SessionState.Live, "state is ${after.state.value}")
        assertEquals("tester_", after.me())
        after.stop()
    }

    /** Someone else really has it, and only two stand-ins are tried before backing off as before. */
    @Test
    fun standInsAreTriedTwiceThenItBacksOff() = runTest {
        val server = Server2018()
        val others = listOf("tester", "tester_", "tester__").map { nick ->
            (server.open("") as Conn).also { server.handle(it, """{"cmd":"join","channel":"testroom","nick":"$nick"}""") }
        }
        server.joins.clear()
        val s = session(server, mayHaveGhost = true)
        s.start(this)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(listOf("tester", "tester_", "tester__"), server.joins)
        assertTrue(s.state.value is SessionState.Reconnecting, "state is ${s.state.value}")
        assertTrue(others.all { it.nick != null })
        s.stop()
    }

    @Test
    fun standInsFitTheNickLimit() {
        assertEquals("bob_", standInNick("bob", 1))
        assertEquals("bob__", standInNick("bob", 2))
        assertEquals("abcdefghijklmnopqrstuvw_", standInNick("abcdefghijklmnopqrstuvwx", 1))
        assertEquals(24, standInNick("abcdefghijklmnopqrstuvwx", 2).length)
    }

    /**
     * A network change closes while the goodbye can still get through, so the
     * nick is free when we come back: no ghost, no stand-in, no backoff.
     */
    @Test
    fun movingNetworksSaysGoodbyeFirst() = runTest {
        val server = Server2018()
        val s = session(server)
        s.start(this)
        advanceUntilIdle()
        val first = server.conns.single()

        s.moveToNewNetwork()
        runCurrent()

        assertTrue(first.saidGoodbye)
        assertEquals(2, server.conns.size, "should reconnect without waiting out a backoff")
        assertEquals(listOf("tester", "tester"), server.joins)
        assertEquals("tester", s.me())
        s.stop()
    }

    /**
     * The service going away cancels the session's scope. The socket used to
     * be left open in the HTTP client, keeping our nick on the server.
     */
    @Test
    fun cancellingTheScopeSaysGoodbye() = runTest {
        val server = Server2018()
        val s = session(server)
        val scope = CoroutineScope(coroutineContext + Job())
        s.start(scope)
        advanceUntilIdle()
        val conn = server.conns.single()

        scope.coroutineContext[Job]!!.cancelAndJoin()

        assertTrue(conn.saidGoodbye, "socket left open")
        assertTrue(server.present.isEmpty())
    }
}
