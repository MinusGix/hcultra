package chat.hc.core.store

/**
 * A plain mutual-exclusion lock for [ChannelBuffer].
 *
 * A buffer is written from more than one coroutine on a multi-threaded
 * dispatcher — the channel's event collector, a send from the service, and the
 * history sweep — and an `ArrayDeque` torn by two of them at once is a crash in
 * the service, not a glitch. Blocking rather than a coroutine Mutex: every
 * critical section is short and none of them suspends.
 */
internal expect class BufferLock() {
    fun <T> withLock(block: () -> T): T
}
