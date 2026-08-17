package chat.hc.ultra.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import chat.hc.core.net.ManagedTransport
import chat.hc.core.session.Credentials
import chat.hc.core.session.ModAction
import chat.hc.core.session.SessionManager
import chat.hc.ultra.data.KeystoreTokenStore
import chat.hc.ultra.ui.NotifyPrefs
import chat.hc.ultra.ui.ServerPrefs
import chat.hc.ultra.ui.ThemePrefs
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

    /**
     * Whether an Activity is bound and on screen.
     *
     * While it is, the channel being read never alerts — the message is already
     * in front of the user — and whether the *other* open tabs still do is
     * [NotifyPrefs.otherChannels].
     *
     * Tracked explicitly rather than inferred from [SessionManager.activeChannel]
     * being non-null, which is also null while the join sheet is up with no
     * channel selected — a state that is very much "in the app".
     */
    var uiForeground: Boolean = false

    inner class LocalBinder : Binder() {
        val service: HcService get() = this@HcService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    /**
     * The channel the user is looking at, or null if none is.
     *
     * Reaching a conversation spends its alerts, whichever way you got there —
     * through the notification, the tab strip, or by returning to an app that
     * was already on it.
     */
    fun showing(channel: String?) {
        sessions.activeChannel = channel
        channel?.let { notifications.clearAlerts(it) }
    }

    override fun onCreate() {
        super.onCreate()
        net = ManagedTransport.create()
        // Read per event rather than captured: the settings sheet writes it in
        // the Activity while this manager lives on in the service, and a
        // SharedPreferences read is cheaper than the wiring to observe one.
        val display = ThemePrefs(this)
        sessions = SessionManager(
            scope = scope,
            initialUrl = ServerPrefs(this).url,
            transport = net.transport,
            tokenStore = KeystoreTokenStore(this),
            showJoinLeave = { display.joinLeave },
            now = { System.currentTimeMillis() },
            // Must be <= 6 chars: chat.js drops a longer customId silently and
            // charges 13 rate-limit points of 25. 36^6 is ample for correlating
            // an echo against the few hundred messages we keep.
            customIdFactory = { Random.nextLong(0, 2_176_782_336L).toString(36).padStart(6, '0') },
        )
        // Channels are registered in HcApp, which has already run by now.
        notifications = ChatNotifications(this)

        // The notification is the app's background presence: it shows which
        // channels are connected and how much is unread, and carries the
        // direct-reply action.
        scope.launch {
            // The map is empty before the first join too, and that must not be
            // read as "the last channel just left".
            var everJoined = false
            sessions.channels.collect { channels ->
                // A closing channel is already gone as far as the user is
                // concerned; it should not be listed, and its unread should not
                // be counted, while it waits out its grace period.
                notifications.update(channels.filterValues { !it.closing })
                if (channels.isNotEmpty()) everJoined = true
                // The last channel leaving for real is what stops the service.
                // Checked here rather than at ACTION_LEAVE because the leave now
                // completes on a timer, long after the intent was handled.
                else if (everJoined) stopSelf()
            }
        }

        // Mentions and whispers, the two things said *to* the user rather than
        // near them. Preferences are read per alert rather than cached: they
        // change from the settings sheet in another process component, and a
        // SharedPreferences read is far cheaper than the wiring to observe it.
        scope.launch {
            val prefs = NotifyPrefs(this@HcService)
            sessions.alerts.collect { alert ->
                if (!prefs.wants(alert.kind)) return@collect
                // A channel the user has closed does not get to buzz them on its
                // way out, even though its socket is briefly still live.
                if (sessions.channels.value[alert.channel]?.closing == true) return@collect
                if (uiForeground) {
                    // The channel on screen never alerts: the message is
                    // already in front of the user.
                    if (alert.channel == sessions.activeChannel) return@collect
                    if (!prefs.otherChannels) return@collect
                }
                notifications.alert(alert)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(
            ChatNotifications.ONGOING_ID,
            notifications.build(sessions.channels.value.filterValues { !it.closing }),
        )

        when (intent?.action) {
            ACTION_JOIN -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                val nick = intent.getStringExtra(EXTRA_NICK) ?: return START_STICKY
                val pass = intent.getStringExtra(EXTRA_PASS)
                scope.launch { sessions.join(channel, Credentials(nick, pass)) }
            }

            ACTION_LEAVE -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                notifications.clearAlerts(channel)
                // Deferred: the socket stays up for the grace period so an undo
                // costs the channel nothing. stopSelf is not called here — the
                // channel is still in the map, and the collector below handles
                // the shutdown once the leave actually commits.
                scope.launch { sessions.beginLeave(channel) }
            }

            ACTION_UNDO_LEAVE -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                scope.launch { sessions.undoLeave(channel) }
            }

            ACTION_SEND -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return START_STICKY
                // Answering is dealing with it. This is what dismisses the
                // notification after a direct reply from the shade, which would
                // otherwise sit there having visibly been replied to already.
                notifications.clearAlerts(channel)
                scope.launch { sessions.sendChat(channel, text) }
            }

            ACTION_MODERATE -> {
                val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: return START_STICKY
                val action = intent.getStringExtra(EXTRA_MOD_ACTION) ?: return START_STICKY
                val userid = intent.getLongExtra(EXTRA_USERID, 0L)
                scope.launch {
                    val target = sessions.channels.value[channel]?.roster
                        ?.firstOrNull { it.userid == userid } ?: return@launch
                    val parsed = runCatching { ModAction.valueOf(action) }.getOrNull() ?: return@launch
                    sessions.moderate(channel, parsed, target)
                }
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
        // The sockets are going with us, so a "reply" action on any surviving
        // alert would silently do nothing.
        notifications.clearAllAlerts()
        scope.launch { sessions.stopAll() }
        net.close()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "HcService"

        const val ACTION_JOIN = "chat.hc.ultra.JOIN"
        const val ACTION_LEAVE = "chat.hc.ultra.LEAVE"
        const val ACTION_UNDO_LEAVE = "chat.hc.ultra.UNDO_LEAVE"
        const val ACTION_SEND = "chat.hc.ultra.SEND"
        const val ACTION_STOP = "chat.hc.ultra.STOP"
        const val ACTION_SET_SERVER = "chat.hc.ultra.SET_SERVER"
        const val ACTION_MODERATE = "chat.hc.ultra.MODERATE"

        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_NICK = "nick"
        const val EXTRA_PASS = "pass"
        const val EXTRA_TEXT = "text"
        const val EXTRA_URL = "url"
        const val EXTRA_MOD_ACTION = "modAction"
        const val EXTRA_USERID = "userid"
    }
}
