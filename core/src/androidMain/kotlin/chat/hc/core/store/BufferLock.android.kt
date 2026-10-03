package chat.hc.core.store

internal actual class BufferLock actual constructor() {
    actual fun <T> withLock(block: () -> T): T = synchronized(this, block)
}
