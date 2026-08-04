package chat.hc.ultra

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import chat.hc.core.render.Scheme
import chat.hc.core.session.ChannelUi
import chat.hc.core.session.SessionState
import chat.hc.ultra.service.HcService
import chat.hc.ultra.ui.ChannelTabs
import chat.hc.ultra.ui.MessageWebView
import chat.hc.ultra.ui.RendererCallbacks
import chat.hc.ultra.ui.SchemeAssets
import chat.hc.ultra.ui.ServerPrefs
import chat.hc.ultra.ui.ThemePrefs
import chat.hc.ultra.ui.ThemeSheet
import chat.hc.ultra.ui.UserList
import chat.hc.ultra.ui.toColorScheme
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

    /** Mirrors the selected tab into the service so unread counts stay right. */
    private var activeChannel: String? = null
        set(value) {
            field = value
            service?.sessions?.activeChannel = value
        }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as HcService.LocalBinder).service
            service = svc
            bound = true
            svc.sessions.activeChannel = activeChannel
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

        val themePrefs = ThemePrefs(this)
        val serverPrefs = ServerPrefs(this)
        val allSchemes = SchemeAssets.load(this)

        setContent {
            var schemeName by remember { mutableStateOf(themePrefs.scheme) }
            var highlightOverride by remember { mutableStateOf(themePrefs.highlightOverride) }
            var showThemes by remember { mutableStateOf(false) }
            var pendingChannel by remember { mutableStateOf<String?>(null) }
            var serverUrl by remember { mutableStateOf(serverPrefs.url) }

            val scheme = remember(schemeName) {
                allSchemes.firstOrNull { it.name == schemeName } ?: allSchemes.first()
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
                            onDismiss = { showThemes = false },
                        )
                    }
                    AppScreen(
                        channelsFlow = channels,
                        onJoin = { channel, nick, pass -> startJoin(channel, nick, pass) },
                        onSend = { channel, text -> startSend(channel, text) },
                        onLeave = { channel -> startLeave(channel) },
                        onActiveChanged = { activeChannel = it },
                        rendererCallbacks = RendererCallbacks(
                            onLinkTap = { url -> openExternal(url) },
                            onChannelTap = { channel -> pendingChannel = channel },
                        ),
                        scheme = scheme,
                        highlight = highlight,
                        onOpenThemes = { showThemes = true },
                        pendingChannel = pendingChannel,
                        onPendingConsumed = { pendingChannel = null },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, HcService::class.java), connection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        // Nothing is showing, so every channel should count unread again.
        service?.sessions?.activeChannel = null
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // The service is intentionally NOT stopped here: it holds the sockets.
    }

    override fun onResume() {
        super.onResume()
        service?.sessions?.activeChannel = activeChannel
    }

    private fun startJoin(channel: String, nick: String, pass: String?) {
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

    /** Links open outside the app; the renderer WebView never navigates. */
    private fun openExternal(url: String) {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return
        if (uri.scheme !in setOf("http", "https")) return
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
}

@Composable
private fun AppScreen(
    channelsFlow: StateFlow<Map<String, ChannelUi>>,
    onJoin: (String, String, String?) -> Unit,
    onSend: (String, String) -> Unit,
    onLeave: (String) -> Unit,
    onActiveChanged: (String?) -> Unit,
    rendererCallbacks: RendererCallbacks,
    scheme: Scheme,
    highlight: String,
    onOpenThemes: () -> Unit,
    /** A tapped `?channel` link pre-fills the join form rather than joining blind. */
    pendingChannel: String?,
    onPendingConsumed: () -> Unit,
) {
    val channels by channelsFlow.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<String?>(null) }
    // A join we asked for that the service has not created yet. Without this,
    // the validity check below races the service and snaps the selection back
    // to the first tab the instant you join a new channel.
    var awaitingJoin by remember { mutableStateOf<String?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    var showUsers by remember { mutableStateOf(false) }
    // One draft per channel: switching tabs must not eat what you were typing.
    val drafts = remember { mutableStateMapOf<String, String>() }

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

    val active = selected?.let { channels[it] }

    LaunchedEffect(pendingChannel) {
        if (pendingChannel != null) showJoin = true
    }

    if (showJoin || ordered.isEmpty()) {
        JoinSheet(
            canCancel = ordered.isNotEmpty(),
            // Reachable before any channel exists: changing server is exactly
            // what you want to do *before* connecting, not after.
            onOpenSettings = onOpenThemes,
            initialChannel = pendingChannel.orEmpty(),
            // Reuse the nick we are already known by; joining a second channel
            // under a different name is possible but almost never intended.
            initialNick = ordered.firstOrNull()?.roster?.firstOrNull { it.isme }?.nick.orEmpty(),
            onJoin = { channel, nick, pass ->
                onJoin(channel, nick, pass)
                selected = channel
                awaitingJoin = channel
                showJoin = false
                onPendingConsumed()
            },
            onCancel = { showJoin = false; onPendingConsumed() },
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
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChannelTabs(
                channels = ordered,
                active = selected,
                onSelect = { selected = it },
                onClose = { onLeave(it) },
                onAdd = { showJoin = true },
            )

            if (active != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        describe(active.state),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { showUsers = !showUsers }) {
                        Text("${active.roster.size} online")
                    }
                    TextButton(onClick = onOpenThemes) { Text("Theme") }
                }

                if (showUsers) {
                    UserList(
                        users = active.roster,
                        onWhisper = { nick ->
                            // The server's /w strips a leading @, so this works
                            // whether or not the nick was mentioned first.
                            drafts[active.channel] = "/w $nick "
                            showUsers = false
                        },
                        onMention = { nick ->
                            // Append rather than replace: mentioning someone
                            // mid-sentence is normal.
                            val current = drafts[active.channel].orEmpty()
                            val sep = if (current.isEmpty() || current.endsWith(" ")) "" else " "
                            drafts[active.channel] = "$current$sep@$nick "
                        },
                    )
                }

                MessageWebView(
                    messages = active.messages,
                    scheme = scheme.name,
                    highlight = highlight,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    callbacks = rendererCallbacks,
                )

                val draft = drafts[active.channel].orEmpty()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { drafts[active.channel] = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                    )
                    Button(
                        onClick = {
                            onSend(active.channel, draft)
                            drafts[active.channel] = ""
                        },
                        enabled = draft.isNotBlank() && active.state is SessionState.Live,
                    ) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun JoinSheet(
    canCancel: Boolean,
    onOpenSettings: () -> Unit,
    initialChannel: String = "",
    initialNick: String = "",
    onJoin: (String, String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    var channelInput by remember(initialChannel) { mutableStateOf(initialChannel) }
    var nickInput by remember(initialNick) { mutableStateOf(initialNick) }

    val submit = {
        val parts = nickInput.split("#", limit = 2)
        onJoin(channelInput.trim(), parts[0].trim(), parts.getOrNull(1))
    }

    if (canCancel) {
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Join a channel") },
            text = {
                JoinFields(channelInput, nickInput, { channelInput = it }, { nickInput = it })
            },
            confirmButton = {
                TextButton(
                    onClick = submit,
                    enabled = channelInput.isNotBlank() && nickInput.isNotBlank(),
                ) { Text("Join") }
            },
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
            JoinFields(channelInput, nickInput, { channelInput = it }, { nickInput = it })
            Button(
                onClick = submit,
                enabled = channelInput.isNotBlank() && nickInput.isNotBlank(),
            ) { Text("Connect") }
        }
    }
}

@Composable
private fun JoinFields(
    channel: String,
    nick: String,
    onChannel: (String) -> Unit,
    onNick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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

private fun describe(state: SessionState): String = when (state) {
    is SessionState.Live -> if (state.restored) "resumed" else "connected"
    is SessionState.Reconnecting -> "reconnecting (attempt ${state.attempt})"
    is SessionState.Failed -> "failed: ${state.reason}"
    SessionState.Connecting, SessionState.Handshaking -> "connecting…"
    SessionState.Idle -> "idle"
}
