package chat.hc.ultra.service

import android.content.Intent
import android.util.Log
import chat.hc.core.session.SessionManager
import chat.hc.core.store.ChatMessage
import chat.hc.core.store.MessageKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Synthetic history for testing long transcripts on a device. Debug builds only:
 * the release source set has a no-op of the same shape, so none of this ships.
 *
 * Driven from adb through [DebugReceiver], against a channel already open in
 * the app:
 *
 *     R=chat.hc.ultra.debug/chat.hc.ultra.service.DebugReceiver
 *     # 10,000 lines spread over the last 3 days
 *     adb shell am broadcast -n $R -a chat.hc.ultra.DEBUG_SEED --es channel test --ei count 10000 --ei days 3
 *     # then a message every 300ms, 200 of them, to watch arrivals
 *     adb shell am broadcast -n $R -a chat.hc.ultra.DEBUG_FLOOD --es channel test --ei count 200 --ei interval 300
 *     # stop a flood early
 *     adb shell am broadcast -n $R -a chat.hc.ultra.DEBUG_STOP
 *
 * Results go to logcat under `HcDebug`.
 */
internal object DebugTools {
    private const val TAG = "HcDebug"
    private var flood: Job? = null
    private var serial = 0
    private var sessions: SessionManager? = null
    private var scope: CoroutineScope? = null

    /** Called by the service as it starts; the receiver has no other way to reach it. */
    fun attach(sessions: SessionManager, scope: CoroutineScope) {
        this.sessions = sessions
        this.scope = scope
    }

    fun handle(intent: Intent) {
        val sessions = sessions
        val scope = scope
        if (sessions == null || scope == null) {
            Log.i(TAG, "service not running; open the app and join a channel first")
            return
        }
        val channel = intent.getStringExtra("channel")
        when (intent.action) {
            "chat.hc.ultra.DEBUG_SEED" -> {
                val count = intent.getIntExtra("count", 10_000)
                val days = intent.getIntExtra("days", 0)
                val now = System.currentTimeMillis()
                // Spread evenly back over [days], so the age limit has something to trim.
                val step = if (days > 0) days * 86_400_000L / count else 1_000L
                val messages = (0 until count).map { i -> sample(now - (count - i) * step) }
                val ok = channel != null && sessions.injectForTesting(channel, messages)
                Log.i(TAG, if (ok) "seeded $count into ?$channel" else "no open channel ?$channel")
            }
            "chat.hc.ultra.DEBUG_FLOOD" -> {
                val count = intent.getIntExtra("count", 200)
                val interval = intent.getIntExtra("interval", 300).toLong()
                flood?.cancel()
                flood = scope.launch {
                    repeat(count) {
                        if (channel == null || !sessions.injectForTesting(channel, listOf(sample(System.currentTimeMillis())))) {
                            Log.i(TAG, "no open channel ?$channel"); return@launch
                        }
                        delay(interval)
                    }
                    Log.i(TAG, "flood of $count into ?$channel done")
                }
            }
            "chat.hc.ultra.DEBUG_STOP" -> flood?.cancel()
        }
    }

    /** Roughly what a channel looks like: mostly chat, some markdown, code, maths, comings and goings. */
    private fun sample(at: Long): ChatMessage {
        val i = serial++
        val nick = "user${i % 37}"
        val (kind, text) = when (i % 100) {
            in 0..4 -> MessageKind.Chat to "look at this:\n```kotlin\nfun f(x: Int) = x * $i\n```"
            in 5..7 -> MessageKind.Chat to "\$\\sum_{k=1}^{$i} k^2 = \\frac{n(n+1)(2n+1)}{6}\$"
            in 8..11 -> MessageKind.Join to ""
            in 12..14 -> MessageKind.Leave to ""
            in 15..19 -> MessageKind.Chat to "**bold** and _italic_ and a link https://example.com/$i and ?programming"
            20 -> MessageKind.Emote to "@$nick waves at line $i"
            else -> MessageKind.Chat to "synthetic line $i — ordinary chat of an ordinary length, for scrolling through"
        }
        return ChatMessage(0, kind, nick = nick, userid = (i % 37).toLong(), text = text, trip = if (i % 3 == 0) "Synth1" else null, at = at)
    }
}
