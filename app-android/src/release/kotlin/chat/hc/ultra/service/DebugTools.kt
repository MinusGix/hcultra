package chat.hc.ultra.service

import chat.hc.core.session.SessionManager
import kotlinx.coroutines.CoroutineScope

/** Release builds have no debug hooks; see the debug source set's version. */
internal object DebugTools {
    @Suppress("UNUSED_PARAMETER")
    fun attach(sessions: SessionManager, scope: CoroutineScope) = Unit
}
