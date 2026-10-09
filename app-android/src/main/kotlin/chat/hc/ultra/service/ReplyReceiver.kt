package chat.hc.ultra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import chat.hc.ultra.service.HcService.Companion.channelKey
import chat.hc.ultra.service.HcService.Companion.putChannel

/** Delivers a direct-reply from the notification into the running service. */
class ReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val channel = intent.channelKey() ?: return
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
                putChannel(channel)
                putExtra(HcService.EXTRA_TEXT, text)
            }
        )
    }

    companion object {
        const val ACTION_REPLY = "chat.hc.ultra.REPLY"
    }
}
