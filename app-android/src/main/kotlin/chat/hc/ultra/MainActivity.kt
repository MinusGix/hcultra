package chat.hc.ultra

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import chat.hc.ultra.ui.ConfirmModeration
import chat.hc.ultra.ui.NickHold
import chat.hc.ultra.ui.NickSheet
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import chat.hc.ultra.data.ImageSources
import chat.hc.ultra.data.TranslatePrefs
import chat.hc.ultra.data.TranslateResult
import chat.hc.ultra.data.Translator
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.render.TranscriptFont
import chat.hc.core.protocol.User
import chat.hc.core.session.Challenge
import chat.hc.core.session.ChannelKey
import chat.hc.core.session.ChannelUi
import chat.hc.ultra.data.ChannelHistory
import chat.hc.ultra.data.ChannelIdentity
import chat.hc.core.session.Ignore
import chat.hc.core.session.IgnoreList
import chat.hc.core.session.IgnoreMode
import chat.hc.core.session.ModAction
import chat.hc.core.session.Moderation
import chat.hc.core.session.Servers
import chat.hc.core.session.SessionState
import chat.hc.core.store.MessageKind
import chat.hc.ultra.service.HcService
import chat.hc.ultra.service.HcService.Companion.channelKey
import chat.hc.ultra.service.HcService.Companion.putChannel
import chat.hc.ultra.ui.ChannelTabs
import chat.hc.ultra.ui.BASE_TEXT_SP
import chat.hc.ultra.ui.composeFamily
import chat.hc.ultra.ui.ImageViewer
import chat.hc.ultra.ui.MessageWebView
import chat.hc.ultra.ui.NotifyPrefs
import chat.hc.ultra.ui.RendererCallbacks
import chat.hc.ultra.ui.SchemeAssets
import chat.hc.ultra.ui.ServerPrefs
import chat.hc.ultra.ui.ThemePrefs
import chat.hc.ultra.ui.resolve
import chat.hc.ultra.ui.ThemeSheet
import chat.hc.ultra.ui.UserList
import chat.hc.ultra.ui.toColorScheme
import chat.hc.ultra.ui.windowBackgroundArgb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Deliberately thin: the Activity renders whatever the service is holding and
 * sends intents back. It owns no connection and no history, so destroying it
 * costs nothing — which is the entire point of the service-owned design.
 */
class MainActivity : ComponentActivity() {

    private var service: HcService? = null
    private val channels = MutableStateFlow<Map<ChannelKey, ChannelUi>>(emptyMap())
    /** The service's ignore list, mirrored like [channels]. */
    private val ignored = MutableStateFlow(IgnoreList())
    private var bound = false

    private val serverPrefs by lazy { ServerPrefs(this) }
    private val history by lazy { ChannelHistory(this) }

    /**
     * Mirrors the selected tab into the service, so unread counts stay right and
     * the channel's notifications clear as soon as you are looking at it.
     */
    private var activeChannel: ChannelKey? = null
        set(value) {
            field = value
            service?.showing(value)
        }

    /** Between onStart and onStop, i.e. while alerts should stay silent. */
    private var visible = false

    /**
     * A channel a notification tap asked for, held until the tab exists. Null
     * once honoured, so returning to the app later does not re-select it.
     */
    private val showChannel = MutableStateFlow<ChannelKey?>(null)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as HcService.LocalBinder).service
            service = svc
            bound = true
            // The bind completes asynchronously, so the service can only learn
            // about a foreground that started before it here.
            svc.uiForeground = visible
            svc.showing(activeChannel)
            // Re-read the service's state; do not reconnect.
            lifecycleScope.launch {
                svc.sessions.channels.collect { channels.value = it }
            }
            lifecycleScope.launch {
                svc.sessions.ignored.collect { ignored.value = it }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Without this the foreground service cannot show its notification,
            // and on modern Android that means it will not survive backgrounding.
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        showChannel.value = intent.channelKey()

        val themePrefs = ThemePrefs(this)
        val notifyPrefs = NotifyPrefs(this)
        val translatePrefs = TranslatePrefs(this)
        val allSchemes = SchemeAssets.load(this)

        // Paint the window from the chosen scheme *before* Compose draws.
        // themes.xml is a static resource and cannot know which of the 44
        // schemes is in force, so without this the first frame is the wrong
        // colour and a light scheme flashes dark.
        applyWindowBackground(allSchemes.resolve(themePrefs.scheme))

        setContent {
            var schemeName by remember { mutableStateOf(themePrefs.scheme) }
            var highlightOverride by remember { mutableStateOf(themePrefs.highlightOverride) }
            var nickLayout by remember { mutableStateOf(themePrefs.nickLayout) }
            var allowImages by remember { mutableStateOf(themePrefs.allowImages) }
            var extraImageSources by remember { mutableStateOf(themePrefs.extraImageSources) }
            var fontScale by remember { mutableFloatStateOf(themePrefs.fontScale) }
            var wrapCode by remember { mutableStateOf(themePrefs.wrapCode) }
            var font by remember { mutableStateOf(themePrefs.font) }
            var joinLeave by remember { mutableStateOf(themePrefs.joinLeave) }
            var ignoreMode by remember { mutableStateOf(themePrefs.ignoreMode) }
            var ignoreFunText by remember { mutableStateOf(themePrefs.ignoreFunText) }
            var historyLines by remember { mutableStateOf(themePrefs.historyLines) }
            var historyDays by remember { mutableStateOf(themePrefs.historyDays) }
            var notifyMentions by remember { mutableStateOf(notifyPrefs.mentions) }
            var notifyWhispers by remember { mutableStateOf(notifyPrefs.whispers) }
            var notifyInvites by remember { mutableStateOf(notifyPrefs.invites) }
            var notifyOtherChannels by remember { mutableStateOf(notifyPrefs.otherChannels) }
            var showThemes by remember { mutableStateOf(false) }
            var pendingChannel by remember { mutableStateOf<ChannelKey?>(null) }
            // An image's URL and its hover text.
            var viewingImage by remember { mutableStateOf<Pair<String, String>?>(null) }
            var translate by remember { mutableStateOf(translatePrefs.enabled) }
            var translateTarget by remember { mutableStateOf(translatePrefs.target) }
            var servers by remember { mutableStateOf(serverPrefs.servers) }
            // The server the join form is on. Remembered identities and
            // "Resume last" are per server, since a trip is: the same password
            // gives a different one on each.
            var joinServer by remember { mutableStateOf(serverPrefs.lastUsed) }

            val scheme = remember(schemeName) { allSchemes.resolve(schemeName) }

            // Keep the window in step with a runtime scheme change, so a later
            // rotation or recreate never repaints from a stale colour.
            LaunchedEffect(scheme) { applyWindowBackground(scheme) }

            // Loaded off the main thread, so it arrives after the first frame;
            // the join screen fills itself in from an effect keyed on the value,
            // which picks it up. `historyVersion` is bumped by anything that
            // writes, since the store is an encrypted blob rather than a flow.
            var historyVersion by remember { mutableIntStateOf(0) }
            var recent by remember { mutableStateOf<List<ChannelIdentity>>(emptyList()) }
            var lastSession by remember { mutableStateOf<List<ChannelIdentity>>(emptyList()) }
            LaunchedEffect(joinServer, historyVersion) {
                recent = history.recent(joinServer)
                lastSession = history.lastSession(joinServer)
            }

            // The trip only exists once the server has derived it, so it can be
            // learned from the roster and nowhere else. This also records which
            // tabs are open, which is what "Resume last" reopens.
            LaunchedEffect(Unit) {
                var previous: Map<String, List<ChannelIdentity>>? = null
                channels.collect { map ->
                    val open = map.values
                        .mapNotNull { ui ->
                            ui.roster.firstOrNull { it.isme }
                                ?.let { ui.server to ChannelIdentity(ui.channel, it.nick, trip = it.trip) }
                        }
                        .groupBy({ it.first }, { it.second })
                    // The roster changes on every join and part in the channel;
                    // rewriting an encrypted blob at that rate would be absurd.
                    if (open == previous) return@collect
                    previous = open
                    open.forEach { (server, identities) ->
                        identities.forEach { history.observeTrip(server, it.channel, it.nick, it.trip) }
                        history.rememberOpen(server, identities)
                    }
                    historyVersion++
                }
            }
            // An explicit pick wins; otherwise follow the scheme's pairing, so
            // changing scheme moves the code colours along with it.
            val highlight = highlightOverride ?: scheme.highlight

            MaterialTheme(colorScheme = scheme.toColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showThemes) {
                        ThemeSheet(
                            schemes = allSchemes,
                            servers = servers,
                            onServersChanged = { updated ->
                                // Anything not carried over unchanged — removed,
                                // or told to speak something else — loses its
                                // open channels; they belong to the old setting.
                                servers.filterNot { it in updated }.forEach { forgetServer(it.url) }
                                servers = updated
                                serverPrefs.servers = updated
                                if (updated.none { it.url == joinServer }) joinServer = updated.first().url
                            },
                            currentScheme = schemeName,
                            highlightOverride = highlightOverride,
                            autoHighlight = scheme.highlight,
                            onSchemeSelected = { schemeName = it; themePrefs.scheme = it },
                            onHighlightSelected = {
                                highlightOverride = it
                                themePrefs.highlightOverride = it
                            },
                            nickLayout = nickLayout,
                            onNickLayoutSelected = {
                                nickLayout = it
                                themePrefs.nickLayout = it
                            },
                            fontScale = fontScale,
                            onFontScaleChanged = {
                                fontScale = it
                                themePrefs.fontScale = it
                            },
                            wrapCode = wrapCode,
                            onWrapCodeChanged = {
                                wrapCode = it
                                themePrefs.wrapCode = it
                            },
                            font = font,
                            onFontChanged = {
                                font = it
                                themePrefs.font = it
                            },
                            allowImages = allowImages,
                            onAllowImagesChanged = {
                                allowImages = it
                                themePrefs.allowImages = it
                            },
                            extraImageSources = extraImageSources,
                            onExtraImageSourcesChanged = {
                                // The pref writes through to ImageSources, which
                                // the gates read; the state change then tells
                                // the page, so the gate is never behind it.
                                themePrefs.extraImageSources = it
                                extraImageSources = it
                            },
                            joinLeave = joinLeave,
                            onJoinLeaveChanged = {
                                joinLeave = it
                                // The service reads the pref itself, per event;
                                // this only decides what the transcript shows of
                                // what is already buffered.
                                themePrefs.joinLeave = it
                            },
                            ignoreMode = ignoreMode,
                            onIgnoreModeChanged = {
                                ignoreMode = it
                                themePrefs.ignoreMode = it
                            },
                            ignoreFunText = ignoreFunText,
                            onIgnoreFunTextChanged = {
                                themePrefs.ignoreFunText = it
                                ignoreFunText = themePrefs.ignoreFunText
                            },
                            historyLines = historyLines,
                            historyDays = historyDays,
                            onHistoryChanged = { lines, days ->
                                themePrefs.historyLines = lines
                                themePrefs.historyDays = days
                                historyLines = themePrefs.historyLines
                                historyDays = themePrefs.historyDays
                                startEnforceHistory()
                            },
                            translate = translate,
                            onTranslateChanged = {
                                translate = it
                                translatePrefs.enabled = it
                            },
                            translateTarget = translateTarget,
                            onTranslateTargetChanged = {
                                translatePrefs.target = it
                                translateTarget = translatePrefs.target
                            },
                            notifyMentions = notifyMentions,
                            onNotifyMentionsChanged = {
                                notifyMentions = it
                                notifyPrefs.mentions = it
                            },
                            notifyWhispers = notifyWhispers,
                            onNotifyWhispersChanged = {
                                notifyWhispers = it
                                notifyPrefs.whispers = it
                            },
                            notifyInvites = notifyInvites,
                            onNotifyInvitesChanged = {
                                notifyInvites = it
                                notifyPrefs.invites = it
                            },
                            notifyOtherChannels = notifyOtherChannels,
                            onNotifyOtherChannelsChanged = {
                                notifyOtherChannels = it
                                notifyPrefs.otherChannels = it
                            },
                            onOpenSystemNotifications = { openNotificationSettings(it) },
                            onOpenLink = { url -> openExternal(url) },
                            onDismiss = { showThemes = false },
                        )
                    }
                    AppScreen(
                        channelsFlow = channels,
                        ignoredFlow = ignored,
                        onSetIgnored = { nick, ignore -> service?.sessions?.setIgnored(nick, ignore) },
                        onSetTripIgnored = { trip, ignore -> service?.sessions?.setTripIgnored(trip, ignore) },
                        servers = servers.map { it.url },
                        joinServer = joinServer,
                        onJoinServer = { joinServer = it },
                        onJoin = { channel, nick, pass -> startJoin(channel, nick, pass) },
                        onSend = { channel, text -> startSend(channel, text) },
                        onLeave = { channel -> startLeave(channel) },
                        onUndoLeave = { channel -> startUndoLeave(channel) },
                        onModerate = { channel, action, target ->
                            startModerate(channel, action, target)
                        },
                        onInvite = { channel, target -> startInvite(channel, target) },
                        onAnswer = { channel, text -> startAnswer(channel, text) },
                        onActiveChanged = { activeChannel = it },
                        recent = recent,
                        lastSession = lastSession,
                        onForget = { identity ->
                            lifecycleScope.launch {
                                history.forget(joinServer, identity)
                                historyVersion++
                            }
                        },
                        rendererCallbacks = RendererCallbacks(
                            onLinkTap = { url -> openExternal(url) },
                            // A link names a channel on the server it was
                            // said on, which is the one being read.
                            onChannelTap = { channel ->
                                pendingChannel = ChannelKey(activeChannel?.server ?: joinServer, channel)
                            },
                            onImageTap = { url, title ->
                                // The page only embeds whitelisted images, but
                                // it renders untrusted text; the viewer is not
                                // opened on its say-so alone.
                                if (ImageSources.allows(url)) viewingImage = url to title
                                else openExternal(url)
                            },
                            onTranslate = { text, reply ->
                                // Checked here as well as by the page having no
                                // button: the page renders untrusted text, and
                                // the setting is what agreed to sending it.
                                if (translatePrefs.enabled) {
                                    val target = translatePrefs.target ?: Translator.phoneTarget()
                                    lifecycleScope.launch { reply(Translator.translate(text, target)) }
                                } else {
                                    reply(TranslateResult.Cancelled)
                                }
                            },
                        ),
                        scheme = scheme,
                        highlight = highlight,
                        nickLayout = nickLayout,
                        fontScale = fontScale,
                        wrapCode = wrapCode,
                        font = font,
                        allowImages = allowImages,
                        extraImageSources = extraImageSources,
                        joinLeave = joinLeave,
                        ignoreMode = ignoreMode,
                        ignoreFunText = ignoreFunText,
                        translate = translate,
                        onOpenThemes = { showThemes = true },
                        pendingChannel = pendingChannel,
                        onPendingConsumed = { pendingChannel = null },
                        showChannel = showChannel.collectAsStateWithLifecycle().value,
                        onShowChannelConsumed = { showChannel.value = null },
                    )
                    viewingImage?.let { (url, title) ->
                        ImageViewer(
                            url = url,
                            caption = title,
                            onDismiss = { viewingImage = null },
                            onOpenOriginal = { openExternal(it) },
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
        service?.uiForeground = true
        bindService(Intent(this, HcService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Nothing is showing, so every channel should count unread again — and
        // alerts start reaching the shade again.
        visible = false
        service?.uiForeground = false
        service?.showing(null)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // The service is intentionally NOT stopped here: it holds the sockets.
    }

    override fun onResume() {
        super.onResume()
        service?.showing(activeChannel)
    }

    /**
     * A notification tap while we are already running. `launchMode=singleTask`
     * means this, not a second onCreate, so the request has to be picked up
     * from here as well as from the launch intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.channelKey()?.let { showChannel.value = it }
    }

    private fun startJoin(channel: ChannelKey, nick: String, pass: String?) {
        // Remembered per server, since a trip is derived from a server-side salt
        // and the same password gives a different trip elsewhere. The trip
        // itself is filled in later, from the roster.
        lifecycleScope.launch {
            history.record(channel.server, ChannelIdentity(channel.channel, nick, pass))
        }
        serverPrefs.lastUsed = channel.server
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_JOIN
                putChannel(channel)
                putExtra(HcService.EXTRA_NICK, nick)
                putExtra(HcService.EXTRA_PASS, pass)
            },
        )
        activeChannel = channel
    }

    /**
     * Leaves every channel open on [url], for a server removed or set to speak
     * something else. Through the binding, as [startEnforceHistory] is: an
     * intent would start the service, and its notification, when there may be
     * nothing on that server to leave.
     */
    private fun forgetServer(url: String) {
        val svc = service ?: return
        lifecycleScope.launch { svc.sessions.forgetServer(url) }
    }

    private fun startModerate(channel: ChannelKey, action: ModAction, target: User) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                this.action = HcService.ACTION_MODERATE
                putChannel(channel)
                putExtra(HcService.EXTRA_MOD_ACTION, action.name)
                putExtra(HcService.EXTRA_USERID, target.userid)
            },
        )
    }

    private fun startInvite(channel: ChannelKey, target: User) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                this.action = HcService.ACTION_INVITE
                putChannel(channel)
                putExtra(HcService.EXTRA_USERID, target.userid)
            },
        )
    }

    /**
     * The service reads the limit itself; this only asks it to apply it now.
     * Through the binding rather than an intent: an intent would start the
     * service, and its notification, with nothing open to apply it to.
     */
    private fun startEnforceHistory() {
        val svc = service ?: return
        lifecycleScope.launch { svc.sessions.enforceHistory() }
    }

    private fun startAnswer(channel: ChannelKey, text: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_ANSWER
                putChannel(channel)
                putExtra(HcService.EXTRA_TEXT, text)
            },
        )
    }

    private fun startLeave(channel: ChannelKey) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_LEAVE
                putChannel(channel)
            },
        )
        if (activeChannel == channel) activeChannel = null
    }

    private fun startUndoLeave(channel: ChannelKey) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_UNDO_LEAVE
                putChannel(channel)
            },
        )
    }

    /**
     * Paints the window itself from the scheme, so the frame that appears
     * before Compose has drawn anything is already the right colour.
     */
    private fun applyWindowBackground(scheme: Scheme) {
        window.setBackgroundDrawable(ColorDrawable(scheme.windowBackgroundArgb()))
        // The status bar draws over our background, so its icons have to follow
        // the scheme too — on a light scheme the default white icons vanish
        // into it completely.
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = !scheme.dark
    }

    /** Links open outside the app; the renderer WebView never navigates. */
    private fun openExternal(url: String) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https")) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /**
     * Hands off to the system's own settings for one notification channel.
     *
     * Sound, vibration and heads-up behaviour have belonged to the platform
     * since Android 8 and cannot be set by the app after a channel exists, so
     * the honest thing is to send the user where the switches actually are
     * rather than to mirror them into a second set that would not work.
     */
    private fun openNotificationSettings(channelId: String) {
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channelId)
            )
        }
    }

    private fun startSend(channel: ChannelKey, text: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_SEND
                putChannel(channel)
                putExtra(HcService.EXTRA_TEXT, text)
            },
        )
    }
}

@Composable
private fun AppScreen(
    channelsFlow: StateFlow<Map<ChannelKey, ChannelUi>>,
    /** Nicks and trips whose messages are hidden; the service owns the list. */
    ignoredFlow: StateFlow<IgnoreList>,
    onSetIgnored: (nick: String, ignore: Boolean) -> Unit,
    onSetTripIgnored: (trip: String, ignore: Boolean) -> Unit,
    /** Every server the user has added, by URL; the join form picks among them. */
    servers: List<String>,
    /** The server the join form is on; [recent] and [lastSession] are its. */
    joinServer: String,
    onJoinServer: (String) -> Unit,
    onJoin: (ChannelKey, String, String?) -> Unit,
    onSend: (ChannelKey, String) -> Unit,
    onLeave: (ChannelKey) -> Unit,
    onUndoLeave: (ChannelKey) -> Unit,
    onModerate: (ChannelKey, ModAction, User) -> Unit,
    onInvite: (ChannelKey, User) -> Unit,
    /** Answers the captcha or password a channel's join is waiting on. */
    onAnswer: (ChannelKey, String) -> Unit,
    onActiveChanged: (ChannelKey?) -> Unit,
    /** Who you have been on [joinServer], most recent first. */
    recent: List<ChannelIdentity>,
    /** The tabs that were open when the app was last used. */
    lastSession: List<ChannelIdentity>,
    onForget: (ChannelIdentity) -> Unit,
    rendererCallbacks: RendererCallbacks,
    scheme: Scheme,
    highlight: String,
    nickLayout: NickLayout,
    /**
     * Transcript text size, as a multiplier on the renderer's base. The
     * composer follows it: what you are about to say should be as readable as
     * what has already been said.
     */
    fontScale: Float,
    /** Wrap long lines in code blocks instead of scrolling them sideways. */
    wrapCode: Boolean,
    /** The transcript's typeface; the composer follows this too. */
    font: TranscriptFont,
    /** Embed whitelisted images in the transcript rather than linking them. */
    allowImages: Boolean,
    /** The user's own image sources, as URL prefixes. */
    extraImageSources: List<String>,
    /**
     * Show arrivals and departures. New ones stop being buffered at all when
     * this is off — the service owns that — so this only governs the ones
     * already held, which would otherwise sit there until they scrolled out and
     * make the switch look like it had not worked.
     */
    joinLeave: Boolean,
    /** What the transcript does with an ignored sender's lines. */
    ignoreMode: IgnoreMode,
    /** What [IgnoreMode.Fun] shows in place of them. */
    ignoreFunText: String,
    /** Whether a tapped message offers Translate. */
    translate: Boolean,
    onOpenThemes: () -> Unit,
    /** A tapped `?channel` link pre-fills the join form rather than joining blind. */
    pendingChannel: ChannelKey?,
    onPendingConsumed: () -> Unit,
    /** A channel a notification tap asked to be shown; already joined. */
    showChannel: ChannelKey?,
    onShowChannelConsumed: () -> Unit,
) {
    val channels by channelsFlow.collectAsStateWithLifecycle()
    // Saveable: the connection outlives the Activity by design, so a rotation
    // or a recreate must not silently move the user to the first tab. It did,
    // and since only the channel on screen is marked read, the tab they were
    // actually reading kept its unread count.
    var selected by rememberSaveable(stateSaver = ChannelKeySaver) { mutableStateOf<ChannelKey?>(null) }
    // A join we asked for that the service has not created yet. Without this,
    // the validity check below races the service and snaps the selection back
    // to the first tab the instant you join a new channel.
    var awaitingJoin by remember { mutableStateOf<ChannelKey?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    // Which channel's roster is open, rather than a bare "is it open" — the way
    // in is a per-tab count, so a channel-blind toggle answered the wrong
    // question: tapping another tab's count closed the roster you were looking
    // at instead of showing that tab's.
    var openRoster by remember { mutableStateOf<ChannelKey?>(null) }
    // One draft per channel: switching tabs must not eat what you were typing.
    // Held as a TextFieldValue rather than a String so a mention can be dropped
    // at the cursor instead of always at the end.
    val drafts = remember { mutableStateMapOf<ChannelKey, TextFieldValue>() }
    // Owned by the service, which also decides unread counts and alerts from
    // it; see SessionManager.ignored.
    val ignored by ignoredFlow.collectAsStateWithLifecycle()
    val toggleIgnore: (String) -> Unit = { nick -> onSetIgnored(nick, nick !in ignored.nicks) }
    val toggleTripIgnore: (String) -> Unit = { trip -> onSetTripIgnored(trip, trip !in ignored.trips) }
    // A held nick, and the channel it was held in: the sheet must act on that
    // channel even if a notification switches tabs underneath it.
    var holding by remember { mutableStateOf<Pair<ChannelKey, NickHold>?>(null) }
    var confirmingMod by remember { mutableStateOf<Triple<ChannelKey, ModAction, User>?>(null) }

    // A closing channel is hidden the instant it is closed, but stays in the map
    // — and on the wire — until its grace period runs out. Everything the tab
    // strip and the selection logic do is in terms of the visible ones.
    val ordered = channels.values.filterNot { it.closing }
    val closing = channels.values.firstOrNull { it.closing }?.key

    // Keep the selection valid as channels come and go, and tell the service
    // which one is on screen so its messages never count as unread.
    LaunchedEffect(channels.keys, closing, selected, awaitingJoin) {
        // Visible keys, not every key: a channel being closed must fall out of
        // the selection immediately, or closing the tab you are reading leaves
        // its transcript on screen for the whole grace period.
        val keys = ordered.mapTo(LinkedHashSet()) { it.key }
        if (awaitingJoin != null && keys.contains(awaitingJoin)) {
            selected = awaitingJoin
            awaitingJoin = null
        }
        val sel = selected
        val valid = when {
            sel != null && keys.contains(sel) -> sel
            awaitingJoin != null -> sel          // join in flight; hold the selection
            else -> ordered.firstOrNull()?.key
        }
        if (valid != selected) selected = valid
        onActiveChanged(valid?.takeIf { keys.contains(it) })
    }

    // A roster only shows for the channel on screen, so leaving that channel has
    // to forget it too. Otherwise the state says "alpha's roster is open" while
    // nothing is on screen, and tapping alpha's count closes the invisible one
    // instead of showing it — a tap that does nothing, which is the exact
    // complaint this set of changes exists to fix.
    LaunchedEffect(selected) {
        if (openRoster != null && openRoster != selected) openRoster = null
    }

    val active = selected?.let { channels[it] }?.takeIf { !it.closing }

    val mention: (String) -> Unit = { nick ->
        active?.key?.let { channel ->
            drafts[channel] = (drafts[channel] ?: TextFieldValue()).withMention(nick)
        }
    }
    // The renderer's bridge is built once, with whatever callbacks the WebView
    // was first composed with, so it gets a stable lambda that reads the
    // current one — otherwise tapping a nick would mention into the channel
    // that happened to be open when the WebView was created.
    val currentMention by rememberUpdatedState(mention)
    val compose: (String) -> Unit = { text ->
        active?.key?.let { channel ->
            drafts[channel] = (drafts[channel] ?: TextFieldValue()).withInsert(text)
        }
    }
    val currentCompose by rememberUpdatedState(compose)
    val haptics = LocalHapticFeedback.current
    val hold: (NickHold) -> Unit = { h ->
        active?.key?.let { channel ->
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            holding = channel to h
        }
    }
    val currentHold by rememberUpdatedState(hold)
    val webCallbacks = remember(rendererCallbacks) {
        rendererCallbacks.copy(
            onNickTap = { nick -> currentMention(nick) },
            onNickHold = { h -> currentHold(h) },
            onCompose = { text -> currentCompose(text) },
        )
    }
    val context = LocalContext.current

    LaunchedEffect(pendingChannel) {
        pendingChannel?.let {
            onJoinServer(it.server)
            showJoin = true
        }
    }

    // A notification tap names the channel it was about. Held until the tab
    // actually exists, because a cold start binds the service after the first
    // composition and the map is empty for a frame or two. Consumed either way
    // once channels arrive: if it is gone by then, the user left it, and
    // silently reselecting it later would be worse than doing nothing.
    LaunchedEffect(showChannel, channels.keys) {
        if (showChannel == null || channels.isEmpty()) return@LaunchedEffect
        if (channels.containsKey(showChannel)) {
            selected = showChannel
            showJoin = false
        }
        onShowChannelConsumed()
    }

    if (showJoin || ordered.isEmpty()) {
        val join = { channel: String, nick: String, pass: String? ->
            val key = ChannelKey(joinServer, channel)
            onJoin(key, nick, pass)
            selected = key
            awaitingJoin = key
            showJoin = false
            onPendingConsumed()
        }
        JoinSheet(
            canCancel = ordered.isNotEmpty(),
            // Reachable before any channel exists: changing server is exactly
            // what you want to do *before* connecting, not after.
            onOpenSettings = onOpenThemes,
            servers = servers,
            server = joinServer,
            onServer = onJoinServer,
            initialChannel = pendingChannel?.channel.orEmpty(),
            recent = recent,
            // Nothing to resume if it is already open — the button would join
            // channels the tab strip is showing.
            lastSession = lastSession.filterNot { channels.containsKey(ChannelKey(joinServer, it.channel)) },
            onJoin = join,
            onResumeLast = { identities ->
                // Only the first is selected; the rest arrive as tabs. Joins are
                // rate-limited server-side (3 of 25, shared across sockets), and
                // RateGovernor paces them, so a wide resume is slow rather than
                // throttled into failure.
                val keys = identities.map { ChannelKey(joinServer, it.channel) }
                identities.zip(keys).forEach { (it, key) -> onJoin(key, it.nick, it.pass) }
                keys.firstOrNull()?.let { selected = it; awaitingJoin = it }
                showJoin = false
                onPendingConsumed()
            },
            onForget = onForget,
            onCancel = { showJoin = false; onPendingConsumed() },
            closing = closing,
            onUndoLeave = onUndoLeave,
        )
        if (ordered.isEmpty()) return
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Without this the composer sits *underneath* the soft keyboard:
                // windowSoftInputMode=adjustResize does not inset Compose content.
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChannelTabs(
                channels = ordered,
                active = selected,
                onSelect = { selected = it },
                onClose = { onLeave(it) },
                onAdd = { showJoin = true },
                onShowRoster = { channel -> openRoster = channel.takeIf { it != openRoster } },
                rosterOpenFor = openRoster,
                onOpenSettings = onOpenThemes,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )

            closing?.let { channel ->
                UndoCloseBar(
                    channel = channel.channel,
                    onUndo = { onUndoLeave(channel) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }

            if (active != null) {
                // Only when something is wrong. "connected" and "resumed" are
                // distinctions this codebase cares about and a reader does not,
                // and the per-tab status dot already carries the rest.
                describe(active.state)?.let { status ->
                    Text(
                        status,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                (active.state as? SessionState.Challenged)?.let { challenged ->
                    // Keyed per challenge, so a retry — which is a new
                    // challenge — starts with an empty field and a live button.
                    androidx.compose.runtime.key(active.key, challenged.serial) {
                        ChallengeCard(
                            channel = active.channel,
                            challenge = challenged.challenge,
                            onAnswer = { onAnswer(active.key, it) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }

                holding?.takeIf { (channel, _) -> channel == active.key }?.let { (channel, h) ->
                    val me = active.roster.firstOrNull { it.isme }
                    // Moderation must reach the connection that sent the
                    // message, so only a userid match will do for it. Whisper
                    // and invite go by whoever holds the nick now, which is
                    // what typing `/w nick` would reach anyway.
                    val sameConnection = active.roster.firstOrNull { h.userid != 0L && it.userid == h.userid }
                    val online = sameConnection ?: active.roster.firstOrNull { it.nick == h.nick }
                    // An outgoing whisper's nick is the recipient's, so being
                    // ours does not make the nick ours.
                    val isSelf = (me != null && online?.userid == me.userid) ||
                        (h.isMine && h.kind != MessageKind.WhisperSent)
                    // The trip to offer ignoring. An outgoing whisper's row
                    // carries ours, so theirs comes from the roster; any other
                    // row carries the sender's own, and only the same
                    // connection may stand in for it, since whoever holds the
                    // nick now may be somebody else entirely.
                    val heldTrip = if (h.kind == MessageKind.WhisperSent) online?.trip
                    else h.trip ?: sameConnection?.trip
                    NickSheet(
                        hold = h,
                        isSelf = isSelf,
                        online = online,
                        modActions = sameConnection?.let { Moderation.available(me, it) }.orEmpty(),
                        ignored = h.nick in ignored.nicks,
                        trip = heldTrip?.takeIf(String::isNotBlank),
                        tripIgnored = heldTrip != null && heldTrip in ignored.trips,
                        onMention = { mention(h.nick) },
                        // `/whisper` rather than `/w`: servers from 2019 know
                        // only the long form, and today's knows both.
                        onWhisper = { drafts[channel] = fieldValue("/whisper ${h.nick} ") },
                        onInvite = { online?.let { onInvite(channel, it) } },
                        onCopy = { text -> copyToClipboard(context, text) },
                        onToggleIgnore = { toggleIgnore(h.nick) },
                        onToggleTripIgnore = { heldTrip?.let(toggleTripIgnore) },
                        onModerate = { action ->
                            if (sameConnection == null) return@NickSheet
                            if (action.destructive) confirmingMod = Triple(channel, action, sameConnection)
                            else onModerate(channel, action, sameConnection)
                        },
                        onDismiss = { holding = null },
                    )
                }
                confirmingMod?.let { (channel, action, target) ->
                    ConfirmModeration(
                        action = action,
                        nick = target.nick,
                        onConfirm = { onModerate(channel, action, target) },
                        onDismiss = { confirmingMod = null },
                    )
                }

                if (openRoster == active.key) {
                    UserList(
                        users = active.roster,
                        isIgnored = { it.nick in ignored.nicks },
                        onToggleIgnore = { toggleIgnore(it.nick) },
                        isTripIgnored = { user -> user.trip?.let { it in ignored.trips } ?: false },
                        onToggleTripIgnore = { user -> user.trip?.takeIf(String::isNotBlank)?.let(toggleTripIgnore) },
                        modActionsFor = { target ->
                            Moderation.available(active.roster.firstOrNull { it.isme }, target)
                        },
                        onModerate = { action, target ->
                            onModerate(active.key, action, target)
                        },
                        onInvite = { target ->
                            onInvite(active.key, target)
                            // Closed so the "You invited …" line the server
                            // sends back is not left behind the roster panel:
                            // that line naming the channel is the only
                            // confirmation the invite went, and the only place
                            // the destination appears.
                            openRoster = null
                        },
                        onWhisper = { nick ->
                            // The server's /whisper strips a leading @, so this
                            // works whether or not the nick was mentioned first.
                            drafts[active.key] = fieldValue("/whisper $nick ")
                            openRoster = null
                        },
                        onMention = { nick -> mention(nick) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                val visible = remember(active.messages, joinLeave, ignored, ignoreMode) {
                    if (joinLeave && ignored.isEmpty()) active.messages
                    else active.messages.mapNotNull {
                        when {
                            !joinLeave && (it.kind == MessageKind.Join || it.kind == MessageKind.Leave) -> null
                            !Ignore.hides(it, ignored) -> it
                            ignoreMode == IgnoreMode.Hide -> null
                            // Kept, for the page to black out or replace; a
                            // copy, so the buffer's own message is untouched.
                            else -> it.copy(veiled = true)
                        }
                    }
                }

                MessageWebView(
                    channel = active.key.viewId,
                    messages = visible,
                    scheme = scheme.name,
                    highlight = highlight,
                    layout = nickLayout,
                    allowImages = allowImages,
                    extraImageSources = extraImageSources,
                    fontScale = fontScale,
                    wrapCode = wrapCode,
                    font = font,
                    translate = translate,
                    ignoreMode = ignoreMode,
                    ignoreFunText = ignoreFunText,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    callbacks = webCallbacks,
                )

                val draft = drafts[active.key] ?: TextFieldValue()
                val canSend = draft.text.isNotBlank() && active.state is SessionState.Live
                val send = {
                    onSend(active.key, draft.text)
                    drafts[active.key] = TextFieldValue()
                }
                // Flush to the bottom edge, with send inside the field rather
                // than beside it — the site's shape, and it stops the composer
                // reading as two separate controls.
                TextField(
                    value = draft,
                    onValueChange = { drafts[active.key] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message") },
                    // The transcript's size, in the field that feeds it. A
                    // setting that made the conversation readable and left what
                    // you type at 15sp would have solved half the problem for
                    // whoever asked for it.
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = (BASE_TEXT_SP * fontScale).sp,
                        lineHeight = (BASE_TEXT_SP * fontScale * 1.45f).sp,
                        fontFamily = font.composeFamily,
                    ),
                    // Five lines of doubled text is most of a phone screen with
                    // the keyboard up — the composer would push the transcript
                    // it belongs to off the top. Fewer, taller lines instead;
                    // the field scrolls either way, so nothing is unreachable.
                    maxLines = if (fontScale > 1.3f) 3 else 5,
                    trailingIcon = {
                        Text(
                            text = "➤",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable(enabled = canSend, onClick = send)
                                .semantics { contentDescription = "Send" }
                                .padding(10.dp),
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        // No underline: the field is the bottom edge, so a
                        // divider under it only draws a second one.
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/**
 * The captcha or password a channel is holding its join for.
 *
 * Inline above the transcript rather than a dialog: it belongs to one channel,
 * and a dialog would cover the others' tabs — including a second channel that
 * could be asking for something too.
 */
@Composable
private fun ChallengeCard(
    channel: String,
    challenge: Challenge,
    onAnswer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var answer by remember { mutableStateOf("") }
    // Sent once per challenge: the card stays up until the server moves the
    // state on, and a second tap would answer a challenge already spent.
    var sent by remember { mutableStateOf(false) }
    // A captcha is case-sensitive and an exact match, so it is never trimmed;
    // a password is whatever the moderator set, spaces included.
    val submit = {
        if (answer.isNotEmpty() && !sent) {
            sent = true
            onAnswer(answer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val heading = when (challenge) {
            is Challenge.Captcha -> "?$channel wants a captcha solved"
            is Challenge.Password -> "?$channel is password protected"
        }
        Text(heading, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        if (challenge.retry) {
            Text(
                when (challenge) {
                    is Challenge.Captcha -> "That was not it. Here is a new one."
                    is Challenge.Password -> "Wrong password."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (challenge is Challenge.Captcha) {
            // Wider than a phone. Scrolled rather than shrunk: art scaled down
            // to fit is art nobody can read, and reading it is the point.
            Text(
                challenge.art,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                lineHeight = 11.sp,
                softWrap = false,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = answer,
                onValueChange = { answer = it },
                singleLine = true,
                enabled = !sent,
                label = {
                    Text(if (challenge is Challenge.Captcha) "Letters (case-sensitive)" else "Channel password")
                },
                visualTransformation = if (challenge is Challenge.Password) PasswordVisualTransformation()
                else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    // Autocorrect "fixing" the case of a captcha is a wrong answer.
                    autoCorrectEnabled = false,
                    keyboardType = if (challenge is Challenge.Password) KeyboardType.Password else KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = submit, enabled = answer.isNotEmpty() && !sent) {
                Text(if (sent) "Joining…" else "Join")
            }
        }
    }
}

/**
 * The take-back offered after closing a channel.
 *
 * This replaces a confirmation dialog, and is better than one for this action
 * specifically. The dialog interrupted every close to ask about the rare
 * mistake, which is why it grew a "don't ask again" — and that switch removed
 * the only guard on an action whose cost is unrecoverable, since hack.chat
 * keeps no history to re-fetch. An undo charges the common case nothing and
 * still catches the mistake.
 *
 * The channel is not actually left until this disappears, so taking it back
 * emits no protocol traffic at all: no `onlineRemove`/`onlineAdd` pair, and the
 * transcript is still in memory where it always was.
 */
@Composable
private fun UndoCloseBar(
    channel: String,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Closed ?$channel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "Undo",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onUndo)
                // Room to actually hit it. The lesson from the roster count:
                // a target this important should not be glyph-sized.
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun JoinSheet(
    canCancel: Boolean,
    onOpenSettings: () -> Unit,
    servers: List<String>,
    server: String,
    onServer: (String) -> Unit,
    initialChannel: String = "",
    recent: List<ChannelIdentity>,
    lastSession: List<ChannelIdentity>,
    onJoin: (String, String, String?) -> Unit,
    onResumeLast: (List<ChannelIdentity>) -> Unit,
    onForget: (ChannelIdentity) -> Unit,
    onCancel: () -> Unit,
    /**
     * A channel closed moments ago, if any. Offered here as well as on the main
     * screen because closing your *last* channel lands you on this sheet — and
     * that is exactly the close most worth taking back, since it is the one that
     * leaves nothing on screen to undo it from.
     */
    closing: ChannelKey? = null,
    onUndoLeave: (ChannelKey) -> Unit = {},
) {
    // Prefilled from the most recent identity, but the *channel* drives the nick
    // from there on: people are routinely a different person in each channel, so
    // the last nick used anywhere is only a starting guess.
    var channelInput by remember(initialChannel) { mutableStateOf(initialChannel) }
    var nickInput by remember { mutableStateOf("") }
    // Typing a nick pins it: adopting a remembered one afterwards would undo
    // what was just typed, which is the very complaint this screen answers.
    // It also protects the field from `recent` reloading underneath it, which
    // happens whenever a channel elsewhere reconnects.
    var nickPinned by remember { mutableStateOf(false) }

    LaunchedEffect(channelInput, recent) {
        if (nickPinned) return@LaunchedEffect
        val known = recent.firstOrNull { it.channel.equals(channelInput.trim(), ignoreCase = true) }
        nickInput = (known ?: recent.firstOrNull())?.fieldText.orEmpty()
    }

    val submit = {
        val parts = nickInput.split("#", limit = 2)
        onJoin(channelInput.trim(), parts[0].trim(), parts.getOrNull(1)?.takeIf(String::isNotEmpty))
    }
    val canSubmit = channelInput.isNotBlank() && nickInput.isNotBlank()

    val body: @Composable () -> Unit = {
        JoinBody(
            servers = servers,
            server = server,
            onServer = onServer,
            channel = channelInput,
            nick = nickInput,
            onChannel = { channelInput = it },
            onNick = { nickInput = it; nickPinned = true },
            recent = recent,
            lastSession = lastSession,
            onPick = { identity ->
                // Straight to connected: the whole point of the list is that the
                // identity is already decided, so making it fill the form and
                // wait for a second tap would be a worse version of typing it.
                onJoin(identity.channel, identity.nick, identity.pass)
            },
            onResumeLast = { onResumeLast(lastSession) },
            onForget = onForget,
        )
    }

    if (canCancel) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Join a channel") },
            text = body,
            confirmButton = { TextButton(onClick = submit, enabled = canSubmit) { Text("Join") } },
            dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        )
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // This branch does not go through Scaffold, so it has to inset
                // for the status and navigation bars itself — without it the
                // header sits underneath the status bar and is untappable.
                .safeDrawingPadding()
                .imePadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Join a channel",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
            closing?.let { channel ->
                UndoCloseBar(channel = channel.channel, onUndo = { onUndoLeave(channel) })
            }
            Column(modifier = Modifier.weight(1f, fill = false)) { body() }
            Button(onClick = submit, enabled = canSubmit) { Text("Connect") }
        }
    }
}

/**
 * Resume, then the remembered identities, then the fields.
 *
 * That order is the claim that the common case is returning somewhere you have
 * already been: typing a channel and a nick from scratch is the fallback, so it
 * sits at the bottom rather than being the only thing on offer.
 */
@Composable
private fun JoinBody(
    servers: List<String>,
    server: String,
    onServer: (String) -> Unit,
    channel: String,
    nick: String,
    onChannel: (String) -> Unit,
    onNick: (String) -> Unit,
    recent: List<ChannelIdentity>,
    lastSession: List<ChannelIdentity>,
    onPick: (ChannelIdentity) -> Unit,
    onResumeLast: () -> Unit,
    onForget: (ChannelIdentity) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // First, because everything under it is that server's: what you
        // resume, who you have been, and where the channel will be joined.
        // Not shown with only one, which is what most people have.
        if (servers.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                servers.forEach { url ->
                    val picked = url == server
                    Text(
                        text = Servers.label(url),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (picked) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (picked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable { onServer(url) }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        if (lastSession.isNotEmpty()) {
            Button(onClick = onResumeLast, modifier = Modifier.fillMaxWidth()) {
                Text("Resume last (" + lastSession.joinToString(", ") { "?" + it.channel } + ")")
            }
        }

        if (recent.isNotEmpty()) {
            Text(
                "Recent",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            recent.forEach { identity ->
                RecentRow(identity, onPick = { onPick(identity) }, onForget = { onForget(identity) })
            }
            HorizontalDivider()
        }

        OutlinedTextField(
            value = channel,
            onValueChange = onChannel,
            label = { Text("Channel") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = nick,
            onValueChange = onNick,
            label = { Text("Nick (add #password for a trip)") },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** `?channel` over `nick trip`, plus a way to forget it. */
@Composable
private fun RecentRow(
    identity: ChannelIdentity,
    onPick: () -> Unit,
    onForget: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("?" + identity.channel, style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    identity.nick,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The trip is the only way to tell two people using the same
                // nick apart, so it earns its place beside every one of them.
                // Before the first connection there is no trip to show yet, only
                // the knowledge that a password is stored.
                identity.trip?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } ?: identity.pass?.let {
                    Text(
                        "tripcode saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        Text(
            "×",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onForget)
                .semantics { contentDescription = "Forget ${identity.nick} in ${identity.channel}" }
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/**
 * The selected tab survives a recreate. Saved as one string: a channel name
 * cannot contain a NUL, so it splits back unambiguously.
 */
private val ChannelKeySaver = Saver<ChannelKey?, String>(
    save = { key -> key?.let { it.server + "\u0000" + it.channel } },
    restore = { saved -> ChannelKey(saved.substringBefore('\u0000'), saved.substringAfter('\u0000')) },
)

/**
 * What the renderer knows a channel by. It keeps one container per id, so the
 * same name on two servers needs two ids; hack.chat's keep the bare name
 * they have always had.
 */
private val ChannelKey.viewId: String
    get() = if (server == Servers.DEFAULT_URL) channel else "$channel\u0000$server"

/** Text the app puts in the composer itself, with the caret left after it. */
private fun fieldValue(text: String) = TextFieldValue(text, TextRange(text.length))

/**
 * Drops `@nick` into the draft where the cursor is, and leaves the cursor after
 * it so typing continues where it left off.
 *
 * At the cursor rather than appended: you notice who you are replying to
 * partway through a sentence as often as before starting one. A selection is
 * replaced, as typing would. Spaces are added only where one is missing, so a
 * mention never arrives glued to the previous word or double-spaced from it.
 */
private fun TextFieldValue.withMention(nick: String): TextFieldValue {
    val before = text.take(selection.min)
    val after = text.substring(selection.max)
    val lead = if (before.isEmpty() || before.last().isWhitespace()) "" else " "
    val trail = if (after.startsWith(" ")) "" else " "
    val insert = "$lead@$nick$trail"
    return TextFieldValue(before + insert + after, TextRange(before.length + insert.length))
}

/**
 * Drops [insert] into the draft as a paste would: over the selection, or at the
 * cursor, with the cursor left after it.
 *
 * Into an empty draft that is simply the message, ready to send again — the
 * case this exists for. Into a draft already under way it is an insertion, not
 * a replacement: a half-written reply is not the app's to throw away because
 * someone tapped a button on a different message.
 */
private fun TextFieldValue.withInsert(insert: String): TextFieldValue {
    val before = text.take(selection.min)
    val after = text.substring(selection.max)
    return TextFieldValue(before + insert + after, TextRange(before.length + insert.length))
}

/**
 * Status worth a line of screen, or null when there is nothing to say.
 *
 * `Live` returns null deliberately: "connected" and "resumed" differ only in
 * whether a token restored us, which matters to this codebase and not to a
 * reader. `Failed` keeps its reason — a red dot alone cannot say *why*.
 */
private fun describe(state: SessionState): String? = when (state) {
    is SessionState.Live -> null
    is SessionState.Reconnecting -> "Reconnecting (attempt ${state.attempt})"
    is SessionState.Failed -> "Failed: ${state.reason}"
    // Has a card of its own, which says more than a status line could.
    is SessionState.Challenged -> null
    SessionState.Connecting, SessionState.Handshaking -> "Connecting…"
    SessionState.Idle -> null
}

private fun copyToClipboard(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("nick", text))
    // Android only confirms a copy on its own from 13 on.
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }
}
