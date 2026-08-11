package chat.hc.ultra

import android.Manifest
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import chat.hc.core.render.NickLayout
import chat.hc.core.render.Scheme
import chat.hc.core.protocol.User
import chat.hc.core.session.ChannelUi
import chat.hc.ultra.data.ChannelHistory
import chat.hc.ultra.data.ChannelIdentity
import chat.hc.core.session.ModAction
import chat.hc.core.session.Moderation
import chat.hc.core.session.SessionState
import chat.hc.ultra.service.HcService
import chat.hc.ultra.ui.ChannelTabs
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
    private val channels = MutableStateFlow<Map<String, ChannelUi>>(emptyMap())
    private var bound = false

    private val serverPrefs by lazy { ServerPrefs(this) }
    private val history by lazy { ChannelHistory(this) }

    /**
     * Mirrors the selected tab into the service, so unread counts stay right and
     * the channel's notifications clear as soon as you are looking at it.
     */
    private var activeChannel: String? = null
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
    private val showChannel = MutableStateFlow<String?>(null)

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

        showChannel.value = intent.getStringExtra(EXTRA_SHOW_CHANNEL)

        val themePrefs = ThemePrefs(this)
        val notifyPrefs = NotifyPrefs(this)
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
            var confirmClose by remember { mutableStateOf(themePrefs.confirmClose) }
            var notifyMentions by remember { mutableStateOf(notifyPrefs.mentions) }
            var notifyWhispers by remember { mutableStateOf(notifyPrefs.whispers) }
            var notifyOtherChannels by remember { mutableStateOf(notifyPrefs.otherChannels) }
            var showThemes by remember { mutableStateOf(false) }
            var pendingChannel by remember { mutableStateOf<String?>(null) }
            var serverUrl by remember { mutableStateOf(serverPrefs.url) }

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
            LaunchedEffect(serverUrl, historyVersion) {
                recent = history.recent(serverUrl)
                lastSession = history.lastSession(serverUrl)
            }

            // The trip only exists once the server has derived it, so it can be
            // learned from the roster and nowhere else. This also records which
            // tabs are open, which is what "Resume last" reopens.
            LaunchedEffect(serverUrl) {
                var previous: List<ChannelIdentity>? = null
                channels.collect { map ->
                    val open = map.values.mapNotNull { ui ->
                        ui.roster.firstOrNull { it.isme }
                            ?.let { ChannelIdentity(ui.channel, it.nick, trip = it.trip) }
                    }
                    // The roster changes on every join and part in the channel;
                    // rewriting an encrypted blob at that rate would be absurd.
                    if (open == previous) return@collect
                    previous = open
                    open.forEach { history.observeTrip(serverUrl, it.channel, it.nick, it.trip) }
                    history.rememberOpen(serverUrl, open)
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
                            currentServer = serverUrl,
                            onServerChanged = { url ->
                                serverUrl = url
                                serverPrefs.url = url
                                switchServer(url)
                                showThemes = false
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
                            confirmClose = confirmClose,
                            onConfirmCloseChanged = {
                                confirmClose = it
                                themePrefs.confirmClose = it
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
                            notifyOtherChannels = notifyOtherChannels,
                            onNotifyOtherChannelsChanged = {
                                notifyOtherChannels = it
                                notifyPrefs.otherChannels = it
                            },
                            onOpenSystemNotifications = { openNotificationSettings(it) },
                            onDismiss = { showThemes = false },
                        )
                    }
                    AppScreen(
                        channelsFlow = channels,
                        onJoin = { channel, nick, pass -> startJoin(channel, nick, pass) },
                        onSend = { channel, text -> startSend(channel, text) },
                        onLeave = { channel -> startLeave(channel) },
                        confirmClose = confirmClose,
                        onStopAskingToClose = {
                            confirmClose = false
                            themePrefs.confirmClose = false
                        },
                        onModerate = { channel, action, target ->
                            startModerate(channel, action, target)
                        },
                        onActiveChanged = { activeChannel = it },
                        recent = recent,
                        lastSession = lastSession,
                        onForget = { identity ->
                            lifecycleScope.launch {
                                history.forget(serverUrl, identity)
                                historyVersion++
                            }
                        },
                        rendererCallbacks = RendererCallbacks(
                            onLinkTap = { url -> openExternal(url) },
                            onChannelTap = { channel -> pendingChannel = channel },
                        ),
                        scheme = scheme,
                        highlight = highlight,
                        nickLayout = nickLayout,
                        onOpenThemes = { showThemes = true },
                        pendingChannel = pendingChannel,
                        onPendingConsumed = { pendingChannel = null },
                        showChannel = showChannel.collectAsStateWithLifecycle().value,
                        onShowChannelConsumed = { showChannel.value = null },
                    )
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
        intent.getStringExtra(EXTRA_SHOW_CHANNEL)?.let { showChannel.value = it }
    }

    private fun startJoin(channel: String, nick: String, pass: String?) {
        // Remembered per server, since a trip is derived from a server-side salt
        // and the same password gives a different trip elsewhere. The trip
        // itself is filled in later, from the roster.
        lifecycleScope.launch {
            history.record(serverPrefs.url, ChannelIdentity(channel, nick, pass))
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_JOIN
                putExtra(HcService.EXTRA_CHANNEL, channel)
                putExtra(HcService.EXTRA_NICK, nick)
                putExtra(HcService.EXTRA_PASS, pass)
            },
        )
        activeChannel = channel
    }

    /**
     * Applies a new endpoint. The service drops every channel and stops, so the
     * next join starts cleanly on the new server; tokens are keyed by server, so
     * the old ones survive for when the user switches back.
     */
    private fun switchServer(url: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_SET_SERVER
                putExtra(HcService.EXTRA_URL, url)
            },
        )
    }

    private fun startModerate(channel: String, action: ModAction, target: User) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                this.action = HcService.ACTION_MODERATE
                putExtra(HcService.EXTRA_CHANNEL, channel)
                putExtra(HcService.EXTRA_MOD_ACTION, action.name)
                putExtra(HcService.EXTRA_USERID, target.userid)
            },
        )
    }

    private fun startLeave(channel: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_LEAVE
                putExtra(HcService.EXTRA_CHANNEL, channel)
            },
        )
        if (activeChannel == channel) activeChannel = null
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

    private fun startSend(channel: String, text: String) {
        ContextCompat.startForegroundService(
            this,
            Intent(this, HcService::class.java).apply {
                action = HcService.ACTION_SEND
                putExtra(HcService.EXTRA_CHANNEL, channel)
                putExtra(HcService.EXTRA_TEXT, text)
            },
        )
    }

    companion object {
        /** Set by an alert notification: the channel it was about. */
        const val EXTRA_SHOW_CHANNEL = "showChannel"
    }
}

@Composable
private fun AppScreen(
    channelsFlow: StateFlow<Map<String, ChannelUi>>,
    onJoin: (String, String, String?) -> Unit,
    onSend: (String, String) -> Unit,
    onLeave: (String) -> Unit,
    /** Whether closing a tab asks first; see [ThemePrefs.confirmClose]. */
    confirmClose: Boolean,
    onStopAskingToClose: () -> Unit,
    onModerate: (String, ModAction, User) -> Unit,
    onActiveChanged: (String?) -> Unit,
    /** Who you have been on this server, most recent first. */
    recent: List<ChannelIdentity>,
    /** The tabs that were open when the app was last used. */
    lastSession: List<ChannelIdentity>,
    onForget: (ChannelIdentity) -> Unit,
    rendererCallbacks: RendererCallbacks,
    scheme: Scheme,
    highlight: String,
    nickLayout: NickLayout,
    onOpenThemes: () -> Unit,
    /** A tapped `?channel` link pre-fills the join form rather than joining blind. */
    pendingChannel: String?,
    onPendingConsumed: () -> Unit,
    /** A channel a notification tap asked to be shown; already joined. */
    showChannel: String?,
    onShowChannelConsumed: () -> Unit,
) {
    val channels by channelsFlow.collectAsStateWithLifecycle()
    // Saveable: the connection outlives the Activity by design, so a rotation
    // or a recreate must not silently move the user to the first tab. It did,
    // and since only the channel on screen is marked read, the tab they were
    // actually reading kept its unread count.
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    // A join we asked for that the service has not created yet. Without this,
    // the validity check below races the service and snaps the selection back
    // to the first tab the instant you join a new channel.
    var awaitingJoin by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    // Which channel's roster is open, rather than a bare "is it open" — the way
    // in is a per-tab count, so a channel-blind toggle answered the wrong
    // question: tapping another tab's count closed the roster you were looking
    // at instead of showing that tab's.
    var openRoster by remember { mutableStateOf<String?>(null) }
    // A close waiting on confirmation. Held by channel rather than a boolean so
    // the dialog can name what it is about to discard.
    var pendingClose by remember { mutableStateOf<String?>(null) }
    // One draft per channel: switching tabs must not eat what you were typing.
    // Held as a TextFieldValue rather than a String so a mention can be dropped
    // at the cursor instead of always at the end.
    val drafts = remember { mutableStateMapOf<String, TextFieldValue>() }

    val ordered = channels.values.toList()

    // Keep the selection valid as channels come and go, and tell the service
    // which one is on screen so its messages never count as unread.
    LaunchedEffect(channels.keys, selected, awaitingJoin) {
        val keys = channels.keys
        if (awaitingJoin != null && keys.contains(awaitingJoin)) {
            selected = awaitingJoin
            awaitingJoin = null
        }
        val sel = selected
        val valid = when {
            sel != null && keys.contains(sel) -> sel
            awaitingJoin != null -> sel          // join in flight; hold the selection
            else -> ordered.firstOrNull()?.channel
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

    val active = selected?.let { channels[it] }

    val mention: (String) -> Unit = { nick ->
        active?.channel?.let { channel ->
            drafts[channel] = (drafts[channel] ?: TextFieldValue()).withMention(nick)
        }
    }
    // The renderer's bridge is built once, with whatever callbacks the WebView
    // was first composed with, so it gets a stable lambda that reads the
    // current one — otherwise tapping a nick would mention into the channel
    // that happened to be open when the WebView was created.
    val currentMention by rememberUpdatedState(mention)
    val webCallbacks = remember(rendererCallbacks) {
        rendererCallbacks.copy(onNickTap = { nick -> currentMention(nick) })
    }

    LaunchedEffect(pendingChannel) {
        if (pendingChannel != null) showJoin = true
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
            onJoin(channel, nick, pass)
            selected = channel
            awaitingJoin = channel
            showJoin = false
            onPendingConsumed()
        }
        JoinSheet(
            canCancel = ordered.isNotEmpty(),
            // Reachable before any channel exists: changing server is exactly
            // what you want to do *before* connecting, not after.
            onOpenSettings = onOpenThemes,
            initialChannel = pendingChannel.orEmpty(),
            recent = recent,
            // Nothing to resume if it is already open — the button would join
            // channels the tab strip is showing.
            lastSession = lastSession.filterNot { channels.containsKey(it.channel) },
            onJoin = join,
            onResumeLast = { identities ->
                // Only the first is selected; the rest arrive as tabs. Joins are
                // rate-limited server-side (3 of 25, shared across sockets), and
                // RateGovernor paces them, so a wide resume is slow rather than
                // throttled into failure.
                identities.forEach { onJoin(it.channel, it.nick, it.pass) }
                identities.firstOrNull()?.let { selected = it.channel; awaitingJoin = it.channel }
                showJoin = false
                onPendingConsumed()
            },
            onForget = onForget,
            onCancel = { showJoin = false; onPendingConsumed() },
        )
        if (ordered.isEmpty()) return
    }

    pendingClose?.let { channel ->
        CloseChannelDialog(
            channel = channel,
            unread = channels[channel]?.unread ?: 0,
            onConfirm = { stopAsking ->
                if (stopAsking) onStopAskingToClose()
                onLeave(channel)
                pendingClose = null
            },
            onDismiss = { pendingClose = null },
        )
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
                onClose = { if (confirmClose) pendingClose = it else onLeave(it) },
                onAdd = { showJoin = true },
                onShowRoster = { channel -> openRoster = channel.takeIf { it != openRoster } },
                rosterOpenFor = openRoster,
                onOpenSettings = onOpenThemes,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
            )

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

                if (openRoster == active.channel) {
                    UserList(
                        users = active.roster,
                        modActionsFor = { target ->
                            Moderation.available(active.roster.firstOrNull { it.isme }, target)
                        },
                        onModerate = { action, target ->
                            onModerate(active.channel, action, target)
                        },
                        onWhisper = { nick ->
                            // The server's /w strips a leading @, so this works
                            // whether or not the nick was mentioned first.
                            drafts[active.channel] = fieldValue("/w $nick ")
                            openRoster = null
                        },
                        onMention = { nick -> mention(nick) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }

                MessageWebView(
                    messages = active.messages,
                    scheme = scheme.name,
                    highlight = highlight,
                    layout = nickLayout,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    callbacks = webCallbacks,
                )

                val draft = drafts[active.channel] ?: TextFieldValue()
                val canSend = draft.text.isNotBlank() && active.state is SessionState.Live
                val send = {
                    onSend(active.channel, draft.text)
                    drafts[active.channel] = TextFieldValue()
                }
                // Flush to the bottom edge, with send inside the field rather
                // than beside it — the site's shape, and it stops the composer
                // reading as two separate controls.
                TextField(
                    value = draft,
                    onValueChange = { drafts[active.channel] = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Message") },
                    maxLines = 5,
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
 * Confirms a close, and offers to stop asking.
 *
 * The offer belongs here rather than only in Settings: someone who finds the
 * prompt unnecessary discovers that at the moment it interrupts them, and
 * making them go hunting for the switch is its own small insult.
 */
@Composable
private fun CloseChannelDialog(
    channel: String,
    unread: Int,
    onConfirm: (stopAsking: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var stopAsking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Close ?$channel?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    buildString {
                        append("You will leave the channel and its history will be discarded — ")
                        append("hack.chat keeps none, so it cannot be fetched again.")
                        if (unread > 0) {
                            append(" There ")
                            append(if (unread == 1) "is 1 unread message." else "are $unread unread messages.")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { stopAsking = !stopAsking }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = stopAsking, onCheckedChange = { stopAsking = it })
                    Text("Don't ask again", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(stopAsking) }) {
                Text("Close", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun JoinSheet(
    canCancel: Boolean,
    onOpenSettings: () -> Unit,
    initialChannel: String = "",
    recent: List<ChannelIdentity>,
    lastSession: List<ChannelIdentity>,
    onJoin: (String, String, String?) -> Unit,
    onResumeLast: (List<ChannelIdentity>) -> Unit,
    onForget: (ChannelIdentity) -> Unit,
    onCancel: () -> Unit,
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
    SessionState.Connecting, SessionState.Handshaking -> "Connecting…"
    SessionState.Idle -> null
}
