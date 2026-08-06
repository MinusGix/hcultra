package chat.hc.ultra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import chat.hc.core.session.Alert
import chat.hc.core.session.ChannelUi
import chat.hc.core.session.SessionState
import chat.hc.core.store.MessageKind
import chat.hc.ultra.MainActivity

/**
 * The persistent notification is this app's whole background story on Android:
 * it is what keeps the process alive, and it doubles as the at-a-glance view of
 * what is connected and what is unread.
 */
class ChatNotifications(private val context: Context) {

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** One alerting notification per channel *and* kind; see [alert]. */
    private data class Key(val channel: String, val kind: Alert.Kind)

    /** What each live alert notification is currently showing, oldest first. */
    private val threads = LinkedHashMap<Key, ArrayDeque<Alert>>()

    fun ensureChannel() {
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            "Connection",
            // Low: the persistent one must be silent. It is infrastructure, not news.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps your chat connections alive while the app is in the background."
            setShowBadge(false)
        }

        // Two channels rather than one so the two can be tuned apart in system
        // settings — silencing whispers overnight while keeping mentions, or the
        // reverse, is a real preference and only the platform can express it.
        //
        // HIGH, and vibrating: these fire only for messages addressed to the
        // user by name, which is the case where a buzz is the whole point. The
        // patterns differ so the two are distinguishable from a pocket: two
        // short taps for a mention, one long one for a whisper.
        val mentions = NotificationChannel(
            CHANNEL_MENTIONS,
            "Mentions",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Someone wrote @yournick in a channel you have joined."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
        }
        val whispers = NotificationChannel(
            CHANNEL_WHISPERS,
            "Whispers",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Someone sent you a private whisper."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400)
        }

        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(mentions)
        manager.createNotificationChannel(whispers)

        // A channel's importance and vibration are fixed at creation and cannot
        // be raised later — the system treats any change as the app overriding
        // the user. Early builds created "messages" at IMPORTANCE_DEFAULT and
        // never posted to it, so upgraders would otherwise be stuck with a dead
        // channel that could never carry these. Deleting it is the only way to
        // start clean, and it costs nothing: nothing was ever posted there.
        manager.deleteNotificationChannel(CHANNEL_LEGACY_MESSAGES)
    }

    fun build(channels: Map<String, ChannelUi>): Notification {
        val connected = channels.values.count { it.state is SessionState.Live }
        val unread = channels.values.sumOf { it.unread }
        val reconnecting = channels.values.any { it.state is SessionState.Reconnecting }

        val title = when {
            channels.isEmpty() -> "Not connected"
            reconnecting -> "Reconnecting…"
            else -> "$connected channel${if (connected == 1) "" else "s"} connected"
        }
        val summary = channels.values.joinToString(", ") { ui ->
            buildString {
                append('?').append(ui.channel)
                if (ui.unread > 0) append(" (").append(ui.unread).append(')')
            }
        }

        val recent = recentLines(channels)

        val builder = NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(chat.hc.ultra.R.drawable.ic_notification)
            .setContentTitle(title)
            // Collapsed shows the latest line, so the notification is worth
            // reading without expanding it; the channel summary moves to the
            // sub-text, which is where it still fits.
            .setContentText(recent.lastOrNull() ?: summary.ifEmpty { "No channels joined" })
            .setSubText(summary.takeIf { it.isNotEmpty() && recent.isNotEmpty() })
            .setOngoing(true)
            .setSilent(true)
            // Every update is a redraw of the same persistent notification, not
            // news. Without this an expanded update can still buzz on some OEM
            // builds even at IMPORTANCE_LOW.
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // PRIVATE, not SECRET: SECRET hides it from the lock screen
            // entirely, which defeats reading the conversation without opening
            // the app. PRIVATE shows it and defers to the system's
            // "hide sensitive content" setting for what to reveal when locked,
            // which is the user's decision to make rather than ours.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(openApp())

        if (recent.isNotEmpty()) {
            builder.setStyle(
                NotificationCompat.InboxStyle().also { style ->
                    recent.forEach(style::addLine)
                    if (summary.isNotEmpty()) style.setSummaryText(summary)
                }
            )
        }

        if (unread > 0) builder.setNumber(unread)

        // Reply straight from the notification, to the most recently active
        // channel — the common case is answering the room you were just in.
        channels.values.firstOrNull()?.let { builder.addAction(replyAction(it.channel)) }
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Disconnect", stopIntent()).build()
        )

        return builder.build()
    }

    fun update(channels: Map<String, ChannelUi>) {
        manager.notify(ONGOING_ID, build(channels))
    }

    /**
     * Posts, or adds to, the alerting notification for one channel.
     *
     * One notification per channel and kind, carrying every alert of that kind
     * since it was last cleared. Three mentions in a busy channel are one
     * conversation and should read as one — posting three notifications would
     * bury the rest of the shade and buzz three times for what is really one
     * "someone wants you".
     *
     * [NotificationCompat.MessagingStyle] rather than a plain text body because
     * the system understands it: it is what gives each line the sender's name,
     * what lets a watch or Auto read the thread aloud, and what makes the
     * inline reply land in the right conversation.
     */
    fun alert(alert: Alert) {
        val key = Key(alert.channel, alert.kind)
        // Swiping a notification away is a statement that the user has seen it,
        // so the next alert must start a fresh conversation rather than
        // resurrecting the lines they just dismissed. Asking the system what is
        // still on screen beats tracking it through a delete intent: it is also
        // correct after the shade is cleared wholesale, or the process restarts.
        if (manager.activeNotifications.none { it.id == idFor(key) }) threads.remove(key)

        val thread = threads.getOrPut(key) { ArrayDeque() }
        thread.addLast(alert)
        // The shade shows a handful at most, and an unbounded list would keep a
        // whole channel's mentions alive for as long as the process lives.
        while (thread.size > THREAD_LINES) thread.removeFirst()

        val self = Person.Builder().setName("You").build()
        val style = NotificationCompat.MessagingStyle(self)
            .setConversationTitle(
                when (alert.kind) {
                    Alert.Kind.Mention -> "?${alert.channel}"
                    Alert.Kind.Whisper -> "Whisper · ?${alert.channel}"
                }
            )
            // True even for whispers: the conversation happened inside a
            // channel, and the title says which one. False collapses the title
            // away on some launchers, which loses the only clue about where to
            // reply.
            .setGroupConversation(true)
        thread.forEach { entry ->
            style.addMessage(
                entry.text.replace('\n', ' ').trim(),
                entry.at,
                Person.Builder().setName(entry.nick).build(),
            )
        }

        val builder = NotificationCompat.Builder(context, channelIdFor(alert.kind))
            .setSmallIcon(chat.hc.ultra.R.drawable.ic_notification)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setWhen(alert.at)
            // Opens straight to the channel it is about, rather than to
            // whichever tab happened to be selected last.
            .setContentIntent(openApp(alert.channel))
            .addAction(replyAction(alert.channel))
        if (thread.size > 1) builder.setNumber(thread.size)

        manager.notify(idFor(key), builder.build())
    }

    /**
     * Drops the alerts for a channel, because the user is now reading it.
     *
     * Called when a channel becomes the visible tab by any route — the
     * notification's own tap, the tab strip, or simply returning to an app that
     * was already showing it. Reaching the conversation is what makes the
     * alert spent; how you got there is not the notification's business.
     */
    fun clearAlerts(channel: String) {
        Alert.Kind.entries.forEach { kind ->
            val key = Key(channel, kind)
            threads.remove(key)
            // Cancelled whether or not we remember posting it: after a restart
            // the shade can still hold alerts this process never saw, and those
            // are exactly the stale ones worth clearing. Cancelling an id that
            // is not showing is a no-op.
            manager.cancel(idFor(key))
        }
    }

    fun clearAllAlerts() {
        threads.keys.toList().forEach { manager.cancel(idFor(it)) }
        threads.clear()
    }

    private fun channelIdFor(kind: Alert.Kind) = when (kind) {
        Alert.Kind.Mention -> CHANNEL_MENTIONS
        Alert.Kind.Whisper -> CHANNEL_WHISPERS
    }

    /**
     * Derived from the channel name, not handed out in sequence.
     *
     * A notification outlives the process that posted it — the service can be
     * killed and restarted with mentions still sitting in the shade — and a
     * counter would restart with them, so the id that used to mean `?one` could
     * come back meaning `?two` and overwrite it. The offset keeps the whole
     * range clear of [ONGOING_ID], which must never be replaced by an alert.
     */
    private fun idFor(key: Key): Int =
        ALERT_ID_BASE + ("${key.channel} ${key.kind.name}".hashCode() and 0xFFFFFF)

    /**
     * The last few messages worth glancing at, oldest first.
     *
     * Only what someone actually said: joins, parts, the MOTD and our own
     * status notices are the bulk of a hack.chat transcript by volume and none
     * of it is worth the two or three lines a notification affords. Our own
     * messages stay — a conversation reads oddly with one side missing, and
     * seeing what you sent last is how you know it went.
     *
     * Ordered across channels by timestamp so the tail really is the tail. The
     * channel is named only when more than one is open, since prefixing every
     * line with the only channel there is wastes the width on a phone.
     */
    private fun recentLines(channels: Map<String, ChannelUi>): List<String> {
        val labelled = channels.size > 1
        return channels.values
            .flatMap { ui -> ui.messages.map { ui.channel to it } }
            .filter { (_, m) -> m.kind in GLANCEABLE }
            .sortedBy { (_, m) -> m.at }
            .takeLast(RECENT_LINES)
            .map { (channel, m) ->
                val body = m.text.replace('\n', ' ').trim()
                val who = when (m.kind) {
                    MessageKind.Emote -> "* ${m.nick}"
                    MessageKind.Whisper -> "${m.nick} whispers"
                    MessageKind.WhisperSent -> "you whisper to ${m.nick}"
                    else -> m.nick
                }
                buildString {
                    if (labelled) append('?').append(channel).append(' ')
                    append(who).append(": ").append(body)
                }
            }
    }

    private fun replyAction(channel: String): NotificationCompat.Action {
        val remoteInput = RemoteInput.Builder(KEY_REPLY)
            .setLabel("Message ?$channel")
            .build()

        val intent = Intent(context, ReplyReceiver::class.java).apply {
            action = ReplyReceiver.ACTION_REPLY
            putExtra(HcService.EXTRA_CHANNEL, channel)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            channel.hashCode(),
            intent,
            // Mutable is required: the system fills the reply text in for us.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        return NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Reply", pending,
        )
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(false)
            .build()
    }

    /**
     * Opens the app, optionally on a named channel.
     *
     * The request code has to vary with the channel: PendingIntents are matched
     * on requestCode and filter alone, and extras are *not* part of that match,
     * so a single code would hand every notification whichever channel was
     * registered first.
     */
    private fun openApp(channel: String? = null): PendingIntent = PendingIntent.getActivity(
        context,
        channel?.let { OPEN_CODE_BASE + (it.hashCode() and 0xFFFFFF) } ?: 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { channel?.let { putExtra(MainActivity.EXTRA_SHOW_CHANNEL, it) } },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent {
        val intent = Intent(context, HcService::class.java).apply {
            action = HcService.ACTION_STOP
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        } else {
            PendingIntent.getService(
                context, 1, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    companion object {
        const val ONGOING_ID = 1001
        const val CHANNEL_ONGOING = "connection"
        const val CHANNEL_MENTIONS = "mentions"
        const val CHANNEL_WHISPERS = "whispers"
        const val KEY_REPLY = "reply_text"

        /** Created but never posted to by builds before mention alerts existed. */
        private const val CHANNEL_LEGACY_MESSAGES = "messages"

        /** Well clear of [ONGOING_ID], which must never be overwritten. */
        private const val ALERT_ID_BASE = 2000
        private const val OPEN_CODE_BASE = 3000

        /** How many lines the expanded notification carries. */
        private const val RECENT_LINES = 3

        /** How many alerts one notification accumulates before dropping the oldest. */
        private const val THREAD_LINES = 6

        /** What counts as conversation, as opposed to transcript bookkeeping. */
        private val GLANCEABLE = setOf(
            MessageKind.Chat,
            MessageKind.Emote,
            MessageKind.Whisper,
            MessageKind.WhisperSent,
        )
    }
}
