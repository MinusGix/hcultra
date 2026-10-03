package chat.hc.core.render

import chat.hc.core.store.ChatMessage

/**
 * Decides what the renderer still needs to be told, and how much of a channel
 * it draws at all.
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
 * So the page keeps one container per channel and this keeps track of what
 * each one holds. Swapping to a channel the page still has costs a `show` and
 * nothing else.
 *
 * **A window, not the whole history.** History can now run to tens of
 * thousands of lines, and a page holding them all pays for every one of them
 * on every arrival: at 10,000 lines one message cost over half a second of
 * layout on a desktop browser (see `tools/renderer-bench.html`). So the page
 * holds the newest [window] rows, grows upward a [chunk] at a time when the
 * reader scrolls towards the top ([older]), and is cut back to [window] once
 * the reader is at the bottom again. What an arrival costs is then bounded by
 * what is drawn, not by what is kept.
 *
 * **Deltas, not lists.** A patch says what to drop, what to add above or
 * below, and what changed in place — never the full order. That holds because
 * the buffer only appends, only trims its oldest end, and keeps `localId`
 * strictly increasing, so the transcript order *is* id order and both sides
 * can place a row from its id alone.
 *
 * Authority lives here rather than in the page, which is what lets the page
 * stay a dumb renderer: it draws what it is given and asks no questions. The
 * cost is that this must never believe the page holds something it does not —
 * hence [reset], which every path that discards DOM goes through.
 */
class TranscriptSync(
    private val residentLimit: Int = DEFAULT_RESIDENT,
    private val window: Int = DEFAULT_WINDOW,
    private val slack: Int = DEFAULT_SLACK,
    private val chunk: Int = DEFAULT_CHUNK,
) {

    /** What the page is holding for one channel: a contiguous run of ids. */
    private class Resident(
        /** Every row the page has, by id. Ordered by id, so first is the top. */
        val rows: LinkedHashMap<Long, ChatMessage>,
        /** Whether the top row is the oldest message there is. */
        var atStart: Boolean,
        /** At the bottom, as the page last said. Only then may the top be cut. */
        var pinned: Boolean = true,
    ) {
        val floor: Long? get() = rows.keys.firstOrNull()
        val last: Long? get() = if (rows.isEmpty()) null else rows.keys.last()
    }

    private val resident = mutableMapOf<String, Resident>()

    /** Channel names, least recently shown first. */
    private val recency = mutableListOf<String>()

    private var visible: String? = null

    /**
     * JS statements that bring the page in line with [messages] for [channel],
     * which becomes the visible one. Empty when it is already correct — which
     * is the common case for a redraw that changed nothing.
     *
     * [messages] must be in `localId` order, as the buffer keeps them.
     */
    fun update(channel: String, messages: List<ChatMessage>): List<String> {
        val calls = mutableListOf<String>()
        val previous = resident[channel]
        val patch = if (previous == null) fresh(channel, messages) else delta(channel, previous, messages)
        patch?.let { calls += it.call(channel) }
        touch(channel)

        if (visible != channel) {
            calls += RendererBridge.showCall(channel)
            visible = channel
        }

        return calls + evictions()
    }

    /**
     * Grows the drawn window upward by a [chunk], for a reader scrolling into
     * older history. Nothing when the page already reaches the start, or holds
     * nothing for [channel] — the page asks again if it still wants more.
     */
    fun older(channel: String, messages: List<ChatMessage>): List<String> {
        val r = resident[channel] ?: return emptyList()
        val floor = r.floor ?: return emptyList()
        val top = lowerBound(messages, floor)
        val from = maxOf(0, top - chunk)
        val prepend = messages.subList(from, top)
        val atStart = from == 0
        if (prepend.isEmpty() && atStart == r.atStart) return emptyList()

        // Rebuilt rather than prepended in place: a LinkedHashMap only appends,
        // and the order is what tells the next delta where the top is. Bounded
        // by what the page is drawing anyway.
        val rows = LinkedHashMap<Long, ChatMessage>(r.rows.size + prepend.size)
        prepend.forEach { rows[it.localId] = it }
        rows.putAll(r.rows)
        resident[channel] = Resident(rows, atStart, r.pinned)
        return listOf(Patch(prepend = prepend, atStart = atStart).call(channel))
    }

    /**
     * Records whether the reader of [channel] is at the bottom. Reaching it is
     * what lets the next update cut a window grown by [older] back down.
     */
    fun setPinned(channel: String, pinned: Boolean) {
        resident[channel]?.pinned = pinned
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

    /** Nothing there to patch: never shown, or evicted to keep the page small. */
    private fun fresh(channel: String, messages: List<ChatMessage>): Patch {
        val from = maxOf(0, messages.size - window)
        val rows = messages.subList(from, messages.size)
        resident[channel] = Resident(rows.associateByTo(LinkedHashMap(rows.size)) { it.localId }, atStart = from == 0)
        return Patch(full = true, append = rows, atStart = from == 0)
    }

    private fun delta(channel: String, r: Resident, messages: List<ChatMessage>): Patch? {
        val last = r.last
        // Where the drawn window starts in the current list: the old top, or
        // whatever now follows it if the buffer has trimmed it away.
        var from = r.floor?.let { lowerBound(messages, it) } ?: maxOf(0, messages.size - window)
        // Rows that arrived since; the list is in id order, so they are a tail.
        val firstNew = if (last == null) from else lowerBound(messages, last + 1)
        val arriving = messages.size - firstNew

        // Cut back to the window when the reader is at the bottom, where the
        // top is out of sight — or when so much arrived that drawing all of it
        // would cost more than starting the reader at the latest.
        val drawn = messages.size - from
        if ((r.pinned && drawn > window + slack) || arriving > window + slack) {
            from = maxOf(from, messages.size - window)
        }
        val atStart = from == 0

        val keep = messages.subList(from, firstNew.coerceAtLeast(from))
        val update = ArrayList<ChatMessage>()
        val kept = HashSet<Long>(keep.size * 2)
        for (m in keep) {
            val had = r.rows[m.localId]
            // A row the page lacks in the middle of what it holds means the two
            // have drifted — a filter switched back on, say. Rare, and a full
            // redraw of the window is the answer that cannot be wrong.
            if (had == null) return fresh(channel, messages).also { resident[channel]?.pinned = r.pinned }
            // Equality short-circuits on identity, and an unchanged message is
            // the same object the buffer already held. Over-sending is harmless;
            // under-sending would leave a stale row on screen.
            if (had != m) update += m
            kept += m.localId
        }
        val drop = r.rows.keys.filter { it !in kept }
        val append = messages.subList(firstNew.coerceAtLeast(from), messages.size)

        if (drop.isEmpty() && update.isEmpty() && append.isEmpty() && atStart == r.atStart) return null

        drop.forEach { r.rows.remove(it) }
        update.forEach { r.rows[it.localId] = it }
        append.forEach { r.rows[it.localId] = it }
        r.atStart = atStart
        return Patch(drop = drop, update = update, append = append, atStart = atStart)
    }

    private fun touch(channel: String) {
        recency.remove(channel)
        recency.add(channel)
    }

    /**
     * Drops the least recently shown channels past the limit.
     *
     * A retained transcript is a window of rendered KaTeX and decoded images;
     * holding one per open tab would trade a swap stall for a memory problem.
     * The channel on screen is never a candidate, however long ago it was last
     * touched — [update] touches it on the way past, so this only matters if
     * the limit is ever set below one.
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

    private class Patch(
        val full: Boolean = false,
        val drop: List<Long> = emptyList(),
        val prepend: List<ChatMessage> = emptyList(),
        val append: List<ChatMessage> = emptyList(),
        val update: List<ChatMessage> = emptyList(),
        val atStart: Boolean,
    ) {
        fun call(channel: String) =
            RendererBridge.applyCall(channel, full, drop, prepend, append, update, atStart)
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

        /** Rows drawn for a reader at the bottom: several screens of scrollback. */
        const val DEFAULT_WINDOW = 300

        /**
         * How far past [DEFAULT_WINDOW] the page may grow before it is cut back.
         * Cutting on every arrival would rebuild the top of the page each time.
         */
        const val DEFAULT_SLACK = 100

        /** Rows added per [older] request. */
        const val DEFAULT_CHUNK = 200

        /** First index in [messages] whose id is at least [id]; the list is in id order. */
        internal fun lowerBound(messages: List<ChatMessage>, id: Long): Int {
            var lo = 0
            var hi = messages.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (messages[mid].localId < id) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }
}
