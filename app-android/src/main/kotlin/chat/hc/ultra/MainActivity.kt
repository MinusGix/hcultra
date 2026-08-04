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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import chat.hc.core.session.ChannelUi
import chat.hc.core.session.SessionState
import chat.hc.core.store.Delivery
import chat.hc.core.store.MessageKind
import chat.hc.ultra.service.HcService
import chat.hc.ultra.ui.MessageWebView
import chat.hc.ultra.ui.RendererCallbacks
import chat.hc.core.render.Scheme
import chat.hc.ultra.ui.SchemeAssets
import chat.hc.ultra.ui.ThemePrefs
import chat.hc.ultra.ui.ThemeSheet
import chat.hc.ultra.ui.toColorScheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Deliberately thin: the Activity renders whatever the service is holding and
 * sends intents back. It owns no connection and no history, so destroying it
 * costs nothing — which is the entire point of the service-owned design.
 *
 * This is scaffolding for the connection work, not the real UI. Message
 * rendering (markdown, KaTeX, code highlighting) lands with the WebView
 * renderer; for now messages are plain text so the transport can be exercised.
 */
class MainActivity : ComponentActivity() {

    private var service: HcService? = null
    private val channels = MutableStateFlow<Map<String, ChannelUi>>(emptyMap())
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as HcService.LocalBinder).service
            service = svc
            bound = true
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
        val allSchemes = SchemeAssets.load(this)

        setContent {
            var schemeName by remember { mutableStateOf(themePrefs.scheme) }
            var highlight by remember { mutableStateOf(themePrefs.highlight) }
            var showThemes by remember { mutableStateOf(false) }
            val scheme = remember(schemeName) {
                allSchemes.firstOrNull { it.name == schemeName } ?: allSchemes.first()
            }

            MaterialTheme(colorScheme = scheme.toColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showThemes) {
                        ThemeSheet(
                            schemes = allSchemes,
                            currentScheme = schemeName,
                            currentHighlight = highlight,
                            onSchemeSelected = { schemeName = it; themePrefs.scheme = it },
                            onHighlightSelected = { highlight = it; themePrefs.highlight = it },
                            onDismiss = { showThemes = false },
                        )
                    }
                    AppScreen(
                        channelsFlow = channels,
                        onJoin = { channel, nick, pass -> startJoin(channel, nick, pass) },
                        onSend = { channel, text -> startSend(channel, text) },
                        rendererCallbacks = RendererCallbacks(
                            onLinkTap = { url -> openExternal(url) },
                            onChannelTap = { channel -> /* TODO: join in a new tab */ },
                        ),
                        scheme = scheme,
                        highlight = highlight,
                        onOpenThemes = { showThemes = true },
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
        if (bound) {
            unbindService(connection)
            bound = false
        }
        // The service is intentionally NOT stopped here: it holds the sockets.
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
    rendererCallbacks: RendererCallbacks,
    scheme: Scheme,
    highlight: String,
    onOpenThemes: () -> Unit,
) {
    val channels by channelsFlow.collectAsStateWithLifecycle()
    var channelInput by remember { mutableStateOf("") }
    var nickInput by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }

    val active = channels.values.firstOrNull()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Without this the composer sits *underneath* the soft keyboard:
                // windowSoftInputMode=adjustResize alone does not inset Compose
                // content, so the input row and Send button become untappable.
                .imePadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (active == null) {
                Text("Join a channel", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = channelInput,
                    onValueChange = { channelInput = it },
                    label = { Text("Channel") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = nickInput,
                    onValueChange = { nickInput = it },
                    label = { Text("Nick (add #password for a trip)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val parts = nickInput.split("#", limit = 2)
                        onJoin(channelInput.trim(), parts[0].trim(), parts.getOrNull(1))
                    },
                    enabled = channelInput.isNotBlank() && nickInput.isNotBlank(),
                ) { Text("Connect") }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "?${active.channel} — ${describe(active.state)} · ${active.roster.size} online",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenThemes) { Text("Theme") }
                }

                MessageWebView(
                    messages = active.messages,
                    scheme = scheme.name,
                    highlight = highlight,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    callbacks = rendererCallbacks,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Message") },
                    )
                    Button(
                        onClick = {
                            onSend(active.channel, draft)
                            draft = ""
                        },
                        enabled = draft.isNotBlank() && active.state is SessionState.Live,
                    ) { Text("Send") }
                }
            }
        }
    }
}

private fun describe(state: SessionState): String = when (state) {
    is SessionState.Live -> if (state.restored) "resumed" else "connected"
    is SessionState.Reconnecting -> "reconnecting (attempt ${state.attempt})"
    is SessionState.Failed -> "failed: ${state.reason}"
    SessionState.Connecting, SessionState.Handshaking -> "connecting…"
    SessionState.Idle -> "idle"
}
