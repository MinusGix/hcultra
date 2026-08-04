package chat.hc.core.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Frames here are captured verbatim from live hack.chat 2.2.3b via probe/run.mjs,
 * so these tests pin the real wire format rather than our idea of it.
 */
class FrameCodecTest {

    @Test
    fun decodesChat() {
        val f = FrameCodec.decode(
            """{"cmd":"chat","nick":"actor8832","uType":"user","userid":6414342456,"channel":"res-jbub5eow","text":"before the drop","level":100,"flair":false,"id":509709,"color":"edb85e","time":1785832964599}"""
        )
        assertIs<Inbound.Chat>(f)
        assertEquals("actor8832", f.nick)
        assertEquals(6414342456L, f.userid)
        assertEquals("before the drop", f.text)
        assertEquals(1785832964599L, f.time)
    }

    @Test
    fun decodesOnlineSetWithUsers() {
        val f = FrameCodec.decode(
            """{"cmd":"onlineSet","nicks":["watcher919","actor8832"],"users":[{"channel":"res-jbub5eow","isme":false,"nick":"watcher919","trip":"","uType":"user","hash":"Ejw6skBnRH9bxSy","level":100,"userid":3256887490278,"color":"5eedaf","flair":false,"online":true},{"isme":true,"isBot":false,"nick":"actor8832","trip":"","uType":"user","hash":"Ejw6skBnRH9bxSy","level":100,"userid":6414342456,"color":"edb85e","flair":false,"channel":"res-jbub5eow"}],"channel":"res-jbub5eow","time":1785832964530}"""
        )
        assertIs<Inbound.OnlineSet>(f)
        assertEquals(2, f.users.size)
        assertEquals(6414342456L, f.users.first { it.isme }.userid)
    }

    @Test
    fun decodesSilentResumeUpdateUser() {
        // The make-before-break handoff signature: updateUser instead of onlineAdd.
        val f = FrameCodec.decode(
            """{"nick":"actor3924","trip":"","uType":"user","hash":"Ejw6skBnRH9bxSy","level":100,"userid":8586059672311,"isBot":false,"color":"5ee8ed","flair":false,"online":true,"cmd":"updateUser","time":1785833016823}"""
        )
        assertIs<Inbound.UpdateUser>(f)
        assertTrue(f.online)
        assertEquals(8586059672311L, f.userid)
    }

    @Test
    fun decodesMultichannelRefusal() {
        val f = FrameCodec.decode(
            """{"cmd":"warn","text":"Joining more than one channel is not currently supported","id":33,"channel":false,"time":1785833243910}"""
        )
        assertIs<Inbound.Warn>(f)
        assertEquals(ErrorId.JOIN_ALREADY_JOINED, f.id)
    }

    @Test
    fun decodesV2Invite() {
        val f = FrameCodec.decode(
            """{"cmd":"invite","channel":"dia-mjjwmnch","from":1557407251814,"to":2325029338562,"inviteChannel":"p7rl8pem","time":1785833160202}"""
        )
        assertIs<Inbound.Invite>(f)
        assertEquals("p7rl8pem", f.inviteChannel)
    }

    @Test
    fun decodesSessionToken() {
        val f = FrameCodec.decode(
            """{"cmd":"session","restored":true,"token":"eyJhbGciOi.abc","channels":["res-jbub5eow"],"time":1785832968684}"""
        )
        assertIs<Inbound.Session>(f)
        assertTrue(f.restored)
        assertEquals(listOf("res-jbub5eow"), f.channels)
    }

    /**
     * Live runs commands absent from the public source (`bomb`, `uwuify`, the
     * wallet set) and gains more each deploy, so an unmodelled cmd must survive.
     */
    @Test
    fun unknownCommandDegradesInsteadOfThrowing() {
        val f = FrameCodec.decode("""{"cmd":"uwuify","text":"owo","time":123}""")
        assertIs<Inbound.Unknown>(f)
        assertEquals("uwuify", f.cmd)
        assertEquals(123L, f.time)
    }

    /** A known cmd carrying an unexpected shape is kept, not dropped. */
    @Test
    fun malformedKnownCommandDegrades() {
        val f = FrameCodec.decode("""{"cmd":"chat","userid":"not-a-number"}""")
        assertIs<Inbound.Unknown>(f)
        assertEquals("chat", f.cmd)
    }

    @Test
    fun ignoresUnmodelledFields() {
        val f = FrameCodec.decode("""{"cmd":"chat","nick":"a","text":"b","somethingNew":{"x":1}}""")
        assertIs<Inbound.Chat>(f)
        assertEquals("b", f.text)
    }

    // ---- outbound ----

    /**
     * `cmd` reaches the wire via the polymorphic class discriminator; the `cmd`
     * properties on Outbound are getter-only and are never serialized.
     */
    @Test
    fun encodesCommandDiscriminator() {
        val obj = Json.parseToJsonElement(FrameCodec.encode(Outbound.Chat("hi"))).jsonObject
        assertEquals("chat", obj["cmd"]?.jsonPrimitive?.content)
        assertEquals("hi", obj["text"]?.jsonPrimitive?.content)
    }

    /** A tokenless session frame is still required: it is what declares v2. */
    @Test
    fun encodesTokenlessSession() {
        val obj = Json.parseToJsonElement(FrameCodec.encode(Outbound.Session(null))).jsonObject
        assertEquals("session", obj["cmd"]?.jsonPrimitive?.content)
        assertTrue("token" !in obj, "null token must be omitted, not sent as null")
    }

    /** whisper requires nick; invite requires numeric userid. See FINDINGS §2. */
    @Test
    fun encodesTargetedCommandsWithRequiredFields() {
        val w = Json.parseToJsonElement(FrameCodec.encode(Outbound.Whisper("ch", "bob", "hi"))).jsonObject
        assertEquals("ch", w["channel"]?.jsonPrimitive?.content)
        assertEquals("bob", w["nick"]?.jsonPrimitive?.content)

        val i = Json.parseToJsonElement(FrameCodec.encode(Outbound.Invite("ch", 42L))).jsonObject
        assertEquals("ch", i["channel"]?.jsonPrimitive?.content)
        assertEquals(42L, i["userid"]?.jsonPrimitive?.content?.toLong())
    }
}
