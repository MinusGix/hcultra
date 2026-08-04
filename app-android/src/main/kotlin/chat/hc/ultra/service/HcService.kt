package chat.hc.ultra.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import chat.hc.core.net.ManagedTransport
import chat.hc.core.session.Credentials
import chat.hc.core.session.SessionManager
import chat.hc.ultra.data.KeystoreTokenStore
import chat.hc.ultra.ui.ServerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * Holds the chat connections for as long as the user is in a conversation.
 *
 * The service — not the Activity — owns [SessionManager], and therefore owns the
 * sockets and the in-memory scrollback. An Activity that is destroyed and
 * recreated (rotation, backgrounding, a notification tap) rebinds and re-reads
 * state instead of reconnecting. That matters more here than in most apps:
 * hack.chat keeps no server-side history, so a reconnect cannot recover what
 * was said, and every reconnect is visible to the whole channel as a
 * leave/join pair.
 */
class HcService : Service() {

    private val scope = CoroutineScope(SupervisorJob())
    private lateinit var net: ManagedTransport
    lateinit var sessions: SessionManager
        private set
    private lateinit var notifications: ChatNotifications

    inner class LocalBinder : Binder() {
        val service: HcService get() = this@HcService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        net = ManagedTransport.create()
        sessions = SessionManager(
            scope = scope,
            initialUrl = ServerPrefs(this).url,
            transport = net.transport,
            tokenStore = KeystoreTokenStore(this),
            now = { System.currentTimeMillis() },
            // Must be <= 6 chars: chat.js drops a longer customId silently and
            // charges 13 rate-limit points of 25. 36^6 is ample for correlating
            // an echo against the few hundred messages we keep.
            customIdFactory = { Random.nextLong(0, 2_176_782_336L).toString(36).padStart(6, '0') },
        )
        notifications = ChatNotifications(this)
        notifications.ensureChannel()

        // The notification is the app's background presence: it shows which
        // channels are connected and how much is unread, and carries the
        // direct-reply action.
        scope.launch {
            sessions.channels.collect { notifications.update(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ChatNotifications.ONGOING_ID, notifications.build(sessions.channels.value))

        when (intent?.action) {
            ACTION_JOIN -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                val nick = intent.getStringExtra(EXTRA_NICK) ?: return START_STICKY
                val pass = intent.getStringExtra(EXTRA_PASS)
                scope.launch { sessions.join(channel, Credentials(nick, pass)) }
            }

            ACTION_LEAVE -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                scope.launch {
                    sessions.leave(channel)
                    if (sessions.channels.value.isEmpty()) stopSelf()
                }
            }

            ACTION_SEND -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                scope.launch { sessions.sendChat(channel, text) }
            }

            ACTION_SET_SERVER -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_STICKY
                // Changing server disconnects every channel: the channels, the
                // nicks and the tokens all belong to the old endpoint.
                scope.launch {
                    sessions.setServer(url)
                    stopSelf()
                }
            }

            ACTION_STOP -> {
                scope.launch {
                    sessions.stopAll()
                    stopSelf()
                }
            }
        }
        // START_STICKY so an OOM kill brings us back; the session token then
        // restores identity without a fresh join.
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "service destroyed")
        scope.launch { sessions.stopAll() }
        net.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HcService"

        const val ACTION_JOIN = "chat.hc.ultra.JOIN"
        const val ACTION_LEAVE = "chat.hc.ultra.LEAVE"
        const val ACTION_SEND = "chat.hc.ultra.SEND"
        const val ACTION_STOP = "chat.hc.ultra.STOP"
        const val ACTION_SET_SERVER = "chat.hc.ultra.SET_SERVER"

        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NICK = "nick"
        const val EXTRA_PASS = "pass"
        const val EXTRA_TEXT = "text"
        const val EXTRA_URL = "url"
    }
}
