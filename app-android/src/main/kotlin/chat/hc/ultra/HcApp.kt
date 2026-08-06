package chat.hc.ultra

import android.app.Application
import chat.hc.ultra.service.ChatNotifications

class HcApp : Application() {

    /**
     * Notification channels are registered here rather than in the service.
     *
     * Application.onCreate runs before any component of the process, so this
     * holds whether we were started by the launcher, by a notification action,
     * or by the system restarting the service — and, less obviously, it means
     * Settings can deep-link to a channel's own sound and vibration screen
     * before the service has ever run. Registering a channel that already
     * exists is a no-op, so the cost is nothing.
     */
    override fun onCreate() {
        super.onCreate()
        ChatNotifications(this).ensureChannel()
    }
}
