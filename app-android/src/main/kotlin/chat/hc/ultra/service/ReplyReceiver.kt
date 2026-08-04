package chat.hc.ultra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput

/** Delivers a direct-reply from the notification into the running service. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val channel = intent.getStringExtra(HcService.EXTRA_CHANNEL) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotifications.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) return

        // The service is already running — it owns the live socket, so this is a
        // hand-off rather than a fresh connection.
        context.startForegroundService(
            Intent(context, HcService::class.java).apply {
                action = HcService.ACTION_SEND
                putExtra(HcService.EXTRA_CHANNEL, channel)
                putExtra(HcService.EXTRA_TEXT, text)
            }
        )
    }

    companion object {
        const val ACTION_REPLY = "chat.hc.ultra.REPLY"
    }
}
