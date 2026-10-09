package chat.hc.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Frames here are verbatim from hack.chat 2.1.91 (December 2019), run with
 * probe/legacy, except where a test says otherwise.
 */
class LegacyAdapterTest {

    private fun LegacyAdapter.read(json: String): List<Inbound> = adapt(FrameCodec.decode(json))
    private fun LegacyAdapter.one(json: String): Inbound = read(json).single()

    /** alice's own adapter, after joining a channel bob is already in. */
    private fun asAlice() = LegacyAdapter().apply {
        one("""{"cmd":"onlineSet","nicks":["bob","alice"],"time":1}""")
    }

    @Test
    fun onlineSetGainsUsersAndKnowsWhichIsUs() {
        val set = assertIs<Inbound.OnlineSet>(
            LegacyAdapter().one("""{"cmd":"onlineSet","nicks":["bob","alice"],"time":1}""")
        )
        assertEquals(listOf("bob", "alice"), set.users.map { it.nick })
        // join.js appends the joiner last; that is the only sign of who we are.
        assertEquals(listOf(false, true), set.users.map { it.isme })
        assertEquals(LegacyAdapter.idFor("alice"), set.users[1].userid)
        assertTrue(set.users.all { it.userid < 0 }, "derived ids must not look issued")
    }

    @Test
    fun derivedIdsIgnoreCase() {
        // 2.1.91 refuses "Bob" while "bob" is present: they are one identity.
        assertEquals(LegacyAdapter.idFor("bob"), LegacyAdapter.idFor("Bob"))
    }

    @Test
    fun onlineAddAndRemoveGainUserids() {
        val a = asAlice()
        val add = assertIs<Inbound.OnlineAdd>(
            a.one("""{"cmd":"onlineAdd","nick":"carol","trip":"null","hash":"XpXWsg6YwcOoWSC","time":2}""")
        )
        assertEquals(LegacyAdapter.idFor("carol"), add.userid)
        assertNull(add.trip, "the string \"null\" is no trip")
        val remove = assertIs<Inbound.OnlineRemove>(a.one("""{"cmd":"onlineRemove","nick":"carol","time":3}"""))
        assertEquals(add.userid, remove.userid)
    }

    @Test
    fun chatGainsTheSendersId() {
        val chat = assertIs<Inbound.Chat>(
            asAlice().one("""{"cmd":"chat","nick":"bob","text":"hi","trip":"t5dInH","time":4}""")
        )
        assertEquals(LegacyAdapter.idFor("bob"), chat.userid)
        assertEquals("t5dInH", chat.trip)
    }

    /**
     * v1 says nothing about levels until a moderator speaks; then the roster
     * hears of it, once, so moderation can be offered from then on.
     */
    @Test
    fun aModeratorsChatPromotesThemInTheRoster() {
        val a = asAlice()
        val first = a.read("""{"cmd":"chat","nick":"bob","text":"hi","mod":true,"trip":"tgM9w/","time":4}""")
        val promoted = assertIs<Inbound.UpdateUser>(first[0])
        assertEquals("bob", promoted.nick)
        assertEquals(Levels.MODERATOR, promoted.level)
        assertEquals(Levels.MODERATOR, assertIs<Inbound.Chat>(first[1]).level)

        val second = a.read("""{"cmd":"chat","nick":"bob","text":"again","mod":true,"time":5}""")
        assertIs<Inbound.Chat>(second.single())
    }

    @Test
    fun infoEmoteBecomesAnEmote() {
        val emote = assertIs<Inbound.Emote>(
            asAlice().one("""{"cmd":"info","type":"emote","nick":"bob","text":"@bob waves","trip":"t5dInH","time":5}""")
        )
        assertEquals("bob", emote.nick)
        assertEquals("@bob waves", emote.text, "as a v2 emote's text reads")
        assertEquals(LegacyAdapter.idFor("bob"), emote.userid)
    }

    @Test
    fun incomingWhisperIsFromThemToUs() {
        val w = assertIs<Inbound.Whisper>(
            asAlice().one("""{"cmd":"info","type":"whisper","from":"bob","trip":"null","text":"bob whispered: psst: a colon","time":6}""")
        )
        assertEquals(LegacyAdapter.idFor("bob"), w.from)
        assertEquals(LegacyAdapter.idFor("alice"), w.to)
        assertEquals("psst: a colon", w.text)
        assertNull(w.trip)
    }

    @Test
    fun ourWhisperEchoIsFromUsToThem() {
        val w = assertIs<Inbound.Whisper>(
            asAlice().one("""{"cmd":"info","type":"whisper","text":"You whispered to @bob: two\nlines","time":6}""")
        )
        assertEquals(LegacyAdapter.idFor("alice"), w.from)
        assertEquals(LegacyAdapter.idFor("bob"), w.to)
        assertEquals("two\nlines", w.text)
    }

    @Test
    fun invitesBothWays() {
        val a = asAlice()
        val incoming = assertIs<Inbound.Invite>(
            a.one("""{"cmd":"info","type":"invite","from":"bob","invite":"88rz4x18","text":"bob invited you to ?88rz4x18","time":7}""")
        )
        assertEquals(LegacyAdapter.idFor("bob"), incoming.from)
        assertEquals("88rz4x18", incoming.inviteChannel)

        val ours = assertIs<Inbound.Invite>(
            a.one("""{"cmd":"info","type":"invite","invite":"88rz4x18","text":"You invited bob to ?88rz4x18","time":7}""")
        )
        assertEquals(LegacyAdapter.idFor("alice"), ours.from)
        assertEquals(LegacyAdapter.idFor("bob"), ours.to)
    }

    /**
     * Today's hack.chat to a v1 socket (`_LegacyFunctions.js`): the userids
     * are real, and are the ones to use, not ones derived from the nick.
     */
    @Test
    fun todaysV1KeepsItsRealUserids() {
        val a = LegacyAdapter()
        a.one("""{"cmd":"onlineSet","users":[{"nick":"bob","userid":7},{"nick":"alice","userid":9,"isme":true}],"nicks":["bob","alice"],"channel":"c"}""")
        val w = assertIs<Inbound.Whisper>(
            a.one("""{"cmd":"info","type":"whisper","from":"bob","to":9,"trip":"null","text":"bob whispered: hi","channel":"c"}""")
        )
        assertEquals(7, w.from)
        assertEquals(9, w.to)
        val invite = assertIs<Inbound.Invite>(
            a.one("""{"cmd":"info","type":"invite","from":"","to":7,"inviteChannel":"x1","text":"You invited bob to ?x1","channel":"c"}""")
        )
        assertEquals(9, invite.from)
        assertEquals(7, invite.to)
    }

    @Test
    fun warningsGainTheIdsTheSessionBranchesOn() {
        val a = LegacyAdapter()
        fun id(text: String) = assertIs<Inbound.Warn>(a.one("""{"cmd":"warn","text":"$text"}""")).id
        assertEquals(ErrorId.BLOCKED, id("You are being rate-limited or blocked."))
        assertEquals(ErrorId.RATELIMIT, id("You are joining channels too fast. Wait a moment and try again."))
        assertEquals(ErrorId.JOIN_NAME_TAKEN, id("Nickname taken"))
        assertEquals(ErrorId.JOIN_INVALID_NICK, id("Nickname must consist of up to 24 letters, numbers, and underscores"))
        assertNull(id("Could not find user in channel"))
    }

    /** A v2 frame must come through untouched: Auto runs everything through this. */
    @Test
    fun v2FramesPassThrough() {
        val a = LegacyAdapter()
        val frames = listOf(
            """{"cmd":"onlineSet","users":[{"isme":true,"nick":"tester","userid":99}],"channel":"c"}""",
            """{"cmd":"chat","nick":"peer","userid":7,"text":"hi","channel":"c"}""",
            """{"cmd":"whisper","from":7,"to":99,"text":"hi","channel":"c"}""",
            """{"cmd":"warn","text":"Nickname taken","id":33}""",
        ).map(FrameCodec::decode)
        frames.forEach { assertEquals(listOf(it), a.adapt(it)) }
    }
}
