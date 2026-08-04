package chat.hc.core.store

import chat.hc.core.protocol.Inbound
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelBufferTest {

    private fun chat(
        text: String,
        customId: String? = null,
        userid: Long = 7L,
        nick: String = "someone",
    ) = Inbound.Chat(nick = nick, userid = userid, text = text, customId = customId, id = 1L)

    // ---- optimistic send reconciliation ----

    @Test
    fun echoReconcilesPendingInsteadOfDuplicating() {
        val b = ChannelBuffer()
        b.addPending("hi", "abc123", "me", 1L, 100L)
        assertEquals(1, b.size)
        assertEquals(Delivery.Sending, b.snapshot().single().delivery)

        b.applyChat(chat("hi", customId = "abc123", userid = 1L, nick = "me"), myUserid = 1L)

        assertEquals(1, b.size, "echo must reconcile, not append a second copy")
        assertEquals(Delivery.Sent, b.snapshot().single().delivery)
    }

    /** The server rewrites some messages (`/shrug`); the echo's text wins. */
    @Test
    fun echoTextOverwritesWhatWeSent() {
        val b = ChannelBuffer()
        b.addPending("/shrug hi", "abc123", "me", 1L, 100L)
        b.applyChat(chat("¯\\_(ツ)_/¯ hi", customId = "abc123", userid = 1L, nick = "me"), myUserid = 1L)
        assertEquals("¯\\_(ツ)_/¯ hi", b.snapshot().single().text)
    }

    @Test
    fun someoneElsesMessageIsAppended() {
        val b = ChannelBuffer()
        b.addPending("mine", "abc123", "me", 1L, 100L)
        b.applyChat(chat("theirs", userid = 9L, nick = "other"), myUserid = 1L)
        assertEquals(2, b.size)
        assertFalse(b.snapshot().last().isMine)
    }

    // ---- unconfirmed ----

    @Test
    fun pendingBecomesUnconfirmedOnTimeout() {
        val b = ChannelBuffer()
        b.addPending("hi", "abc123", "me", 1L, 100L)
        assertTrue(b.markUnconfirmed("abc123"))
        assertEquals(Delivery.Unconfirmed, b.snapshot().single().delivery)
    }

    /** A message already echoed must never be downgraded by a late timer. */
    @Test
    fun reconciledMessageIsNotDowngraded() {
        val b = ChannelBuffer()
        b.addPending("hi", "abc123", "me", 1L, 100L)
        b.applyChat(chat("hi", customId = "abc123", userid = 1L, nick = "me"), myUserid = 1L)

        assertFalse(b.markUnconfirmed("abc123"), "should report no change")
        assertEquals(Delivery.Sent, b.snapshot().single().delivery)
    }

    @Test
    fun disconnectDowngradesEveryPendingMessage() {
        val b = ChannelBuffer()
        b.addPending("one", "id1", "me", 1L, 100L)
        b.addPending("two", "id2", "me", 1L, 101L)
        b.applyChat(chat("one", customId = "id1", userid = 1L, nick = "me"), myUserid = 1L)

        assertTrue(b.markAllPendingUnconfirmed())
        val byId = b.snapshot().associateBy { it.customId }
        assertEquals(Delivery.Sent, byId["id1"]?.delivery, "already echoed, must stay Sent")
        assertEquals(Delivery.Unconfirmed, byId["id2"]?.delivery)
    }

    /**
     * A slow round trip can outlive the echo timeout. The late echo must
     * resolve the message, not append a second copy of it.
     */
    @Test
    fun lateEchoReconcilesAnUnconfirmedMessage() {
        val b = ChannelBuffer()
        b.addPending("hi", "abc123", "me", 1L, 100L)
        b.markUnconfirmed("abc123")
        assertEquals(Delivery.Unconfirmed, b.snapshot().single().delivery)

        b.applyChat(chat("hi", customId = "abc123", userid = 1L, nick = "me"), myUserid = 1L)

        assertEquals(1, b.size, "late echo must not duplicate the message")
        assertEquals(Delivery.Sent, b.snapshot().single().delivery)
    }

    @Test
    fun unknownCustomIdIsIgnored() {
        val b = ChannelBuffer()
        assertFalse(b.markUnconfirmed("nope"))
    }

    // ---- streaming updates ----

    @Test
    fun updateModesApply() {
        val b = ChannelBuffer()
        b.add(ChatMessage(0, MessageKind.Chat, text = "base", customId = "s1"))

        b.applyUpdate(Inbound.UpdateMessage(mode = "append", text = "+A", customId = "s1"))
        assertEquals("base+A", b.snapshot().single().text)

        b.applyUpdate(Inbound.UpdateMessage(mode = "prepend", text = "P-", customId = "s1"))
        assertEquals("P-base+A", b.snapshot().single().text)

        b.applyUpdate(Inbound.UpdateMessage(mode = "overwrite", text = "new", customId = "s1"))
        assertEquals("new", b.snapshot().single().text)

        b.applyUpdate(Inbound.UpdateMessage(mode = "complete", text = "!", customId = "s1"))
        assertEquals("new!", b.snapshot().single().text)
        assertTrue(b.snapshot().single().streamComplete)
    }

    /** Applying a partial edit to the wrong message is worse than dropping it. */
    @Test
    fun updateForUnknownCustomIdIsDropped() {
        val b = ChannelBuffer()
        b.add(ChatMessage(0, MessageKind.Chat, text = "base", customId = "s1"))
        assertNull(b.applyUpdate(Inbound.UpdateMessage(mode = "append", text = "x", customId = "other")))
        assertEquals("base", b.snapshot().single().text)
    }

    // ---- ring behaviour ----

    @Test
    fun oldestMessagesAreTrimmedAtCapacity() {
        val b = ChannelBuffer(capacity = 3)
        repeat(5) { b.add(ChatMessage(0, MessageKind.Chat, text = "m$it")) }
        assertEquals(3, b.size)
        assertEquals(listOf("m2", "m3", "m4"), b.snapshot().map { it.text })
    }

    /** A trimmed message's customId must not linger and mis-target a later edit. */
    @Test
    fun trimmingForgetsCustomIds() {
        val b = ChannelBuffer(capacity = 2)
        b.add(ChatMessage(0, MessageKind.Chat, text = "old", customId = "gone"))
        b.add(ChatMessage(0, MessageKind.Chat, text = "a"))
        b.add(ChatMessage(0, MessageKind.Chat, text = "b"))

        assertNull(b.applyUpdate(Inbound.UpdateMessage(mode = "append", text = "!", customId = "gone")))
        assertEquals(listOf("a", "b"), b.snapshot().map { it.text })
    }

    @Test
    fun localIdsAreUniqueAndOrdered() {
        val b = ChannelBuffer()
        val ids = (1..4).map { b.add(ChatMessage(0, MessageKind.Chat, text = "m$it")).localId }
        assertEquals(ids.sorted(), ids)
        assertEquals(ids.toSet().size, ids.size)
        assertNotNull(b.snapshot().firstOrNull())
    }
}
