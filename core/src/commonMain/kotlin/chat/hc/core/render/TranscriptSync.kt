package chat.hc.core.render

import chat.hc.core.store.ChatMessage

/**
 * Decides what the renderer still needs to be told.
 *
 * The renderer used to be handed the whole visible transcript on every change,
 * on the reasoning that the buffer is bounded so a full snapshot stays cheap.
 * Measured on the emulator, at 257 messages that is a 51KB payload serialized,
 * shipped across the bridge and re-parsed *per arriving message* — work that
 * grows with how long you have been in the room, to change one row.
 *
 * Worse, the page keyed its nodes by `localId` alone, and every [ChatMessage]
 * id starts at 1 in its own channel, so two channels collide. Swapping tabs
 * matched channel B's row *n* against channel A's row *n*, found them
 * different, and rewrote it — 250 of 257 rows re-rendered through markdown,
 * KaTeX and highlight.js, every `<img>` destroyed and rebuilt, 181ms of
 * blocking work for a tab tap.
 *
 * So the page now keeps one container per channel and this keeps track of what
 * each one holds. Swapping to a channel the page still has costs a `show` and
 * nothing else; an arriving message costs the ids plus the one row that
 * changed.
 *
 * Authority lives here rather than in the page, which is what lets the page
 * stay a dumb renderer: it draws what it is given and asks no questions. The
 * cost is that this must never believe the page holds something it does not —
 * hence [reset], which every path that discards DOM goes through.
 */
class TranscriptSync(private val residentLimit: Int = DEFAULT_RESIDENT) {

    /** What the page is holding for one channel. */
    private class Resident(val order: List<Long>, val byId: Map<Long, ChatMessage>)

    private val resident = mutableMapOf<String, Resident>()

    /** Channel names, least recently shown first. */
    private val recency = mutableListOf<String>()

    private var visible: String? = null

    /**
     * JS statements that bring the page in line with [messages] for [channel],
     * which becomes the visible one. Empty when it is already correct — which
     * is the common case for a redraw that changed nothing.
     */
    fun update(channel: String, messages: List<ChatMessage>): List<String> {
        val calls = mutableListOf<String>()
        val order = messages.map { it.localId }
        val previous = resident[channel]

        if (previous == null) {
            // Nothing there to patch: this channel has never been shown, or was
            // evicted to keep the page's memory bounded.
            calls += RendererBridge.applyCall(channel, full = true, order = order, upsert = messages)
        } else {
            // Compared by identity of the whole message, not a signature of the
            // fields we happen to render. A ChatMessage is immutable, so a
            // changed one is a new object and an unchanged one is the same
            // object the buffer already held — the map costs references, not
            // copies. Over-sending is harmless; under-sending would leave a
            // stale row on screen, so the comparison errs towards the former.
            val upsert = messages.filter { previous.byId[it.localId] != it }
            if (upsert.isNotEmpty() || previous.order != order) {
                calls += RendererBridge.applyCall(channel, full = false, order = order, upsert = upsert)
            }
        }

        resident[channel] = Resident(order, messages.associateBy { it.localId })
        touch(channel)

        if (visible != channel) {
            calls += RendererBridge.showCall(channel)
            visible = channel
        }

        return calls + evictions()
    }

    /**
     * Forgets everything the page was holding.
     *
     * For when the DOM is gone from under us: the WebView reloaded, or the
     * images setting changed, which the page answers by dropping every
     * container it has. Skipping this would leave us patching rows that are not
     * there, and the page would sit half-empty with no way to notice.
     */
    fun reset() {
        resident.clear()
        recency.clear()
        visible = null
    }

    private fun touch(channel: String) {
        recency.remove(channel)
        recency.add(channel)
    }

    /**
     * Drops the least recently shown channels past the limit.
     *
     * A retained transcript is up to 500 rows of rendered KaTeX and decoded
     * images; holding one per open tab would trade a swap stall for a memory
     * problem. The channel on screen is never a candidate, however long ago it
     * was last touched — [update] touches it on the way past, so this only
     * matters if the limit is ever set below one.
     */
    private fun evictions(): List<String> {
        val calls = mutableListOf<String>()
        while (resident.size > residentLimit) {
            val victim = recency.firstOrNull { it != visible } ?: break
            resident.remove(victim)
            recency.remove(victim)
            calls += RendererBridge.evictCall(victim)
        }
        return calls
    }

    companion object {
        /**
         * How many channels keep their DOM.
         *
         * Three covers the shape of the problem — flicking between two rooms,
         * and glancing at a third — without holding a transcript for every tab
         * somebody has left open since this morning.
         */
        const val DEFAULT_RESIDENT = 3
    }
}
