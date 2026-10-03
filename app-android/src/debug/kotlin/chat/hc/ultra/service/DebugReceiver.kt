package chat.hc.ultra.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * adb's way in to [DebugTools]. Exported, but only to senders holding
 * `android.permission.DUMP` — which `adb shell` has and an installed app cannot
 * — so no other app on the phone can stuff a channel through it.
 */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = DebugTools.handle(intent)
}
