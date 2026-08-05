package chat.hc.ultra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
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
        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "Messages",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "New messages in channels you have joined." }

        manager.createNotificationChannel(ongoing)
        manager.createNotificationChannel(messages)
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

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
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
        const val CHANNEL_MESSAGES = "messages"
        const val KEY_REPLY = "reply_text"

        /** How many lines the expanded notification carries. */
        private const val RECENT_LINES = 3

        /** What counts as conversation, as opposed to transcript bookkeeping. */
        private val GLANCEABLE = setOf(
            MessageKind.Chat,
            MessageKind.Emote,
            MessageKind.Whisper,
            MessageKind.WhisperSent,
        )
    }
}
