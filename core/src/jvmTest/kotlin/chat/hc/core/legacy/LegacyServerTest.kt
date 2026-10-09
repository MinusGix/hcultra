package chat.hc.core.legacy

import chat.hc.core.net.KtorTransport
import chat.hc.core.session.ChannelKey
import chat.hc.core.session.Credentials
import chat.hc.core.session.Dialect
import chat.hc.core.session.ModAction
import chat.hc.core.session.SessionManager
import chat.hc.core.session.SessionState
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The client against hack.chat 2.1.91, as some instances still run it.
 * Run with `./gradlew :core:legacyTest`; see probe/legacy/legacy.mjs.
 *
 * Every manager here is on [Dialect.Auto], so each test also checks that a
 * server with no `session` is recognised for what it is — the setting most
 * people will leave it on.
 */
class LegacyServerTest {

    private val url = System.getenv("LEGACY_URL") ?: "ws://127.0.0.1:6080"
    private val transport by lazy { KtorTransport(KtorTransport.defaultClient(HttpClient(CIO))) }

    private fun legacy(block: suspend CoroutineScope.(managers: MutableList<SessionManager>) -> Unit) = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val managers = mutableListOf<SessionManager>()
        try {
            withTimeout(60_000) { scope.block(managers) }
        } finally {
            managers.forEach { it.stopAll() }
            scope.cancel()
        }
    }

    /** One person's app: its own manager, as two phones would have. */
    private fun CoroutineScope.app(managers: MutableList<SessionManager>): SessionManager {
        var ids = 0
        return SessionManager(
            scope = this,
            transport = transport,
            now = { System.currentTimeMillis() },
            customIdFactory = { (++ids).toString().padStart(6, '0') },
            dialectFor = { Dialect.Auto },
        ).also { managers += it }
    }

    private fun room() = "legacy${Random.nextInt(100_000, 999_999)}"

    private suspend fun waitUntil(label: String, timeoutMillis: Long = 10_000, pred: () -> Boolean) {
        try {
            withTimeout(timeoutMillis) { while (!pred()) delay(50) }
        } catch (e: Exception) {
            throw AssertionError("timed out waiting for $label", e)
        }
    }

    private fun SessionManager.ui(key: ChannelKey) = channels.value.getValue(key)

    @Test
    fun autoRecognisesTheServerAndJoins() = legacy { managers ->
        val alice = app(managers)
        val key = ChannelKey(url, room())
        alice.join(key, Credentials("alice", "pw"))
        waitUntil("alice live") { alice.ui(key).state is SessionState.Live }
        assertEquals(listOf("alice"), alice.ui(key).roster.map { it.nick })
        assertTrue(alice.ui(key).roster.single().isme)
    }

    @Test
    fun aConversation() = legacy { managers ->
        val alice = app(managers)
        val bob = app(managers)
        val key = ChannelKey(url, room())
        alice.join(key, Credentials("alice", "pw"))
        waitUntil("alice live") { alice.ui(key).state is SessionState.Live }
        bob.join(key, Credentials("bob"))
        waitUntil("bob live") { bob.ui(key).state is SessionState.Live }
        waitUntil("alice sees bob") { alice.ui(key).roster.any { it.nick == "bob" } }

        // Our own message, echoed without a customId, is still reconciled.
        alice.sendChat(key, "hello\n\n\n\nthere")
        waitUntil("bob hears alice") { bob.ui(key).messages.any { it.kind == MessageKind.Chat } }
        waitUntil("alice's echo") {
            alice.ui(key).messages.filter { it.kind == MessageKind.Chat }.all { it.delivery == Delivery.Sent }
        }
        val mine = alice.ui(key).messages.filter { it.kind == MessageKind.Chat }
        assertEquals(1, mine.size, "echo duplicated: $mine")
        assertTrue(mine.single().isMine)
        val heard = bob.ui(key).messages.single { it.kind == MessageKind.Chat }
        assertEquals("alice", heard.nick)
        assertTrue(heard.trip != null, "the trip came through")

        bob.sendChat(key, "/me waves")
        waitUntil("alice sees the emote") {
            alice.ui(key).messages.any { it.kind == MessageKind.Emote && it.nick == "bob" && it.text == "@bob waves" }
        }

        // 2.1.91 has /whisper but not /w.
        bob.sendChat(key, "/whisper alice psst")
        waitUntil("alice is whispered to") {
            alice.ui(key).messages.any { it.kind == MessageKind.Whisper && it.nick == "bob" && it.text == "psst" }
        }
        waitUntil("bob sees his whisper") {
            bob.ui(key).messages.any { it.kind == MessageKind.WhisperSent && it.nick == "alice" && it.text == "psst" }
        }

        val bobInRoster = alice.ui(key).roster.first { it.nick == "bob" }
        assertTrue(alice.invite(key, bobInRoster))
        waitUntil("bob is invited") {
            bob.ui(key).messages.any { it.kind == MessageKind.Info && it.text.startsWith("alice invited you to ?") }
        }

        bob.leave(key)
        waitUntil("alice sees bob go") { alice.ui(key).roster.none { it.nick == "bob" } }
    }

    /** v1 moderation is by nick, and a moderator is known once they speak. */
    @Test
    fun aModeratorKicks() = legacy { managers ->
        val mod = app(managers)
        val troll = app(managers)
        val key = ChannelKey(url, room())
        mod.join(key, Credentials("amod", "legacy-mod"))
        troll.join(key, Credentials("troll"))
        waitUntil("both live") {
            mod.ui(key).state is SessionState.Live && troll.ui(key).state is SessionState.Live
        }
        waitUntil("mod sees troll") { mod.ui(key).roster.any { it.nick == "troll" } }

        mod.sendChat(key, "behave")
        waitUntil("mod knows it is a mod") { (mod.me(key)?.level ?: 0) > 0 }

        val target = mod.ui(key).roster.first { it.nick == "troll" }
        assertTrue(mod.moderate(key, ModAction.Kick, target))
        waitUntil("troll is gone") { mod.ui(key).roster.none { it.nick == "troll" } }
    }
}
