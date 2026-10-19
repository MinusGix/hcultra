package chat.hc.core.session

import chat.hc.core.FakeTransport
import chat.hc.core.protocol.Levels
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
 * Moderation through the manager on a v2 connection. A refused command costs
 * 10 rate-limit points of 25 and gets no reply, so the manager re-checks our
 * level as it stands now rather than trusting whatever button was tapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerModerateTest {

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

    /** Us as `alice`/99 at [myLevel], with `bob`/7, an ordinary user with no hash. */
    private fun FakeTransport.scriptJoin(myLevel: Int) {
        onSend = { raw ->
            when (parse(raw).str("cmd")) {
                "session" -> serverSends("""{"cmd":"session","restored":false,"token":"","channels":[]}""")
                "join" -> serverSends(
                    """{"cmd":"onlineSet","users":[""" +
                        """{"isme":true,"nick":"alice","userid":99,"level":$myLevel},""" +
                        """{"isme":false,"nick":"bob","userid":7,"level":${Levels.DEFAULT}}""" +
                        """],"channel":"testroom"}"""
                )
            }
        }
    }

    private fun TestScope.manager(transport: FakeTransport) = SessionManager(
        scope = this,
        transport = transport,
        now = { 0L },
        customIdFactory = { "abc123" },
    )

    private fun SessionManager.bob() =
        channels.value.getValue(key("testroom")).roster.first { it.nick == "bob" }

    @Test
    fun aModeratorKicksByUseridInTheChannel() = runTest {
        val transport = FakeTransport().apply { scriptJoin(Levels.MODERATOR) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        assertTrue(sessions.moderate(key("testroom"), ModAction.Kick, sessions.bob()))
        val kick = parse(transport.latest.sent.last())
        assertEquals("kick", kick.str("cmd"))
        assertEquals("testroom", kick.str("channel"))
        assertEquals("7", kick.str("userid"))
        assertNull(kick["nick"], "v2 is addressed by userid only")
        sessions.stopAll()
    }

    @Test
    fun anOrdinaryUserSendsNothing() = runTest {
        val transport = FakeTransport().apply { scriptJoin(Levels.DEFAULT) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        val before = transport.latest.sent.size

        assertFalse(sessions.moderate(key("testroom"), ModAction.Kick, sessions.bob()))
        assertEquals(before, transport.latest.sent.size, "a refused kick was sent anyway")
        sessions.stopAll()
    }

    /** A button drawn while we were a moderator must stop working once we are not. */
    @Test
    fun aDemotionIsHonouredBeforeTheNextCommand() = runTest {
        val transport = FakeTransport().apply { scriptJoin(Levels.MODERATOR) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        val bob = sessions.bob()

        transport.latest.serverSends(
            """{"cmd":"updateUser","nick":"alice","userid":99,"level":${Levels.DEFAULT},"channel":"testroom"}"""
        )
        advanceUntilIdle()
        assertEquals(Levels.DEFAULT, sessions.me(key("testroom"))?.level)
        val before = transport.latest.sent.size

        assertFalse(sessions.moderate(key("testroom"), ModAction.Ban, bob))
        assertEquals(before, transport.latest.sent.size)
        sessions.stopAll()
    }

    /** `speak` is keyed on a hash; with none there is nothing to send. */
    @Test
    fun unmuzzlingSomeoneWithNoHashSendsNothing() = runTest {
        val transport = FakeTransport().apply { scriptJoin(Levels.MODERATOR) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()
        val before = transport.latest.sent.size

        assertFalse(sessions.moderate(key("testroom"), ModAction.Unmuzzle, sessions.bob()))
        assertEquals(before, transport.latest.sent.size)
        sessions.stopAll()
    }

    @Test
    fun aChannelWeAreNotInIsRefused() = runTest {
        val transport = FakeTransport().apply { scriptJoin(Levels.MODERATOR) }
        val sessions = manager(transport)
        sessions.join(key("testroom"), Credentials("alice"))
        advanceUntilIdle()

        assertFalse(sessions.moderate(key("elsewhere"), ModAction.Kick, sessions.bob()))
        sessions.stopAll()
    }
}
