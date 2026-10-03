package chat.hc.core.bench

import chat.hc.core.render.TranscriptSync
import chat.hc.core.store.ChannelBuffer
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind

/**
 * What one arriving message costs on the native side, by history length.
 *
 * Drives the real per-message path — buffer add, the snapshot SessionManager
 * publishes, TranscriptSync's diff and the bridge call it produces — against a
 * channel already holding N messages. Not part of `check`; run with:
 *
 *     ./gradlew :core:transcriptBench
 */
fun main() {
    val sizes = listOf(500, 2_000, 10_000, 50_000)
    println("%8s %14s %14s %16s".format("history", "µs/message", "bridge bytes", "full render bytes"))
    for (n in sizes) {
        val buffer = ChannelBuffer(capacity = n)
        repeat(n) { buffer.add(sample(it)) }
        val sync = TranscriptSync()
        val initial = sync.update("bench", buffer.snapshot()).sumOf { it.length }

        // Warm up the JIT on the steady state before timing it.
        var i = n
        repeat(2_000) { step(buffer, sync, sample(i++)) }

        val rounds = 2_000
        var bytes = 0L
        val start = System.nanoTime()
        repeat(rounds) { bytes += step(buffer, sync, sample(i++)) }
        val micros = (System.nanoTime() - start) / 1_000.0 / rounds
        println("%8d %14.1f %14d %16d".format(n, micros, bytes / rounds, initial))
    }
}

/** One arriving message: what onEvent + publish + MessageWebView do. Returns bridge bytes. */
private fun step(buffer: ChannelBuffer, sync: TranscriptSync, m: ChatMessage): Int {
    buffer.add(m)
    val snapshot = buffer.snapshot()
    return sync.update("bench", snapshot).sumOf { it.length }
}

private fun sample(i: Int) = ChatMessage(
    localId = 0,
    kind = MessageKind.Chat,
    nick = "user${i % 40}",
    userid = (i % 40).toLong(),
    text = "message number $i with an ordinary amount of text in it, like most chat",
    at = i.toLong(),
)
