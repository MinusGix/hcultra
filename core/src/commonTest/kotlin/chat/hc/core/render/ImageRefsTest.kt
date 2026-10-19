package chat.hc.core.render

import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageRefsTest {

    @Test
    fun findsEveryImageInAMessage() {
        val text = "look ![a](https://i.imgur.com/a.png) and ![](https://i.ibb.co/x/b.jpg) too"
        assertEquals(
            listOf("https://i.imgur.com/a.png", "https://i.ibb.co/x/b.jpg"),
            ImageRefs.of(text).toList(),
        )
    }

    @Test
    fun stopsBeforeATitle() {
        assertEquals(
            listOf("https://i.imgur.com/a.png"),
            ImageRefs.of("![a](https://i.imgur.com/a.png \"title\")").toList(),
        )
    }

    @Test
    fun acceptsAngleBrackets() {
        assertEquals(
            listOf("https://i.imgur.com/a.png"),
            ImageRefs.of("![a](<https://i.imgur.com/a.png>)").toList(),
        )
    }

    @Test
    fun plainLinksAreNotImages() {
        assertTrue(ImageRefs.of("[a](https://i.imgur.com/a.png) https://i.imgur.com/b.png").none())
    }

    @Test
    fun gathersAcrossMessages() {
        val messages = listOf(
            ChatMessage(1, MessageKind.Chat, text = "![](https://i.imgur.com/a.png)"),
            ChatMessage(2, MessageKind.Emote, text = "@x ![](https://i.imgur.com/b.png)"),
            ChatMessage(3, MessageKind.Chat, text = "again ![](https://i.imgur.com/a.png)"),
            ChatMessage(4, MessageKind.Chat, text = "no pictures"),
        )
        assertEquals(setOf("https://i.imgur.com/a.png", "https://i.imgur.com/b.png"), ImageRefs.of(messages))
    }
}
