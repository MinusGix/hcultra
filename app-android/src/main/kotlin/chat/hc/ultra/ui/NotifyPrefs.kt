package chat.hc.ultra.ui

import android.content.Context
import chat.hc.core.session.Alert

/**
 * Which kinds of message are worth interrupting the user for.
 *
 * Deliberately only *whether*, never *how*. Since Android 8 the sound, the
 * vibration and the heads-up behaviour belong to the notification channel and
 * are the user's to change from system settings; an in-app "vibrate" switch
 * could not honestly implement them — setting them at post time is ignored —
 * and would leave two controls disagreeing about the same thing. Settings links
 * out to the real ones instead.
 *
 * All default on. A mention and a whisper are the two things in a hack.chat
 * channel that are addressed to you specifically, and someone who installs a
 * chat client is asking to hear about those; ordinary channel traffic stays
 * silent, which is what makes these safe to default on.
 */
class NotifyPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("hc_notify", Context.MODE_PRIVATE)

    /** `@yournick` in a channel you have joined. */
    var mentions: Boolean
        get() = prefs.getBoolean(KEY_MENTIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_MENTIONS, value).apply()

    /** A whisper sent to you. */
    var whispers: Boolean
        get() = prefs.getBoolean(KEY_WHISPERS, true)
        set(value) = prefs.edit().putBoolean(KEY_WHISPERS, value).apply()

    /**
     * Whether the two above still fire for a channel you are *not* looking at
     * while the app is open.
     *
     * On by default. Several open tabs is the normal way to use this app, and
     * being called in one of them is no less worth knowing about because you
     * happen to be reading another — the tab's unread badge says a message
     * arrived, not that it was addressed to you. Turning this off is the
     * "I am busy in this conversation" setting.
     *
     * The channel actually on screen never alerts either way: you are looking
     * straight at it.
     */
    var otherChannels: Boolean
        get() = prefs.getBoolean(KEY_OTHER_CHANNELS, true)
        set(value) = prefs.edit().putBoolean(KEY_OTHER_CHANNELS, value).apply()

    fun wants(kind: Alert.Kind): Boolean = when (kind) {
        Alert.Kind.Mention -> mentions
        Alert.Kind.Whisper -> whispers
    }

    private companion object {
        const val KEY_MENTIONS = "mentions"
        const val KEY_WHISPERS = "whispers"
        const val KEY_OTHER_CHANNELS = "other_channels"
    }
}
