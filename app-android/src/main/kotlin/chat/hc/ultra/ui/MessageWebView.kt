package chat.hc.ultra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import chat.hc.core.render.NickLayout
import chat.hc.core.render.RendererBridge
import chat.hc.core.store.ChatMessage

/**
 * Message transcript rendered by the site's own markdown + KaTeX + highlight.js
 * pipeline, running in a WebView over `assets/renderer/`.
 *
 * Using the real pipeline rather than a native text renderer is a deliberate
 * trade: it costs a WebView, and buys exact parity with hack.chat for markdown,
 * `$math$`, code highlighting, `?channel` links and typographer quirks — which
 * no native reimplementation would match, least of all for KaTeX.
 */
data class RendererCallbacks(
    val onLinkTap: (String) -> Unit = {},
    val onChannelTap: (String) -> Unit = {},
    /** A tapped nick: mention them in the composer. */
    val onNickTap: (String) -> Unit = {},
)

private class Bridge(
    private val callbacks: RendererCallbacks,
    private val ready: () -> Unit,
) {
    @JavascriptInterface
    fun onReady(unused: String) = ready()

    @JavascriptInterface
    fun onLinkTap(url: String) = callbacks.onLinkTap(url)

    @JavascriptInterface
    fun onChannelTap(channel: String) = callbacks.onChannelTap(channel)

    /**
     * Bounced to the main thread: unlike the taps above, which hand off to the
     * system or to a one-shot state write, this one edits the composer's
     * [androidx.compose.ui.text.input.TextFieldValue], and bridge calls arrive
     * on a WebView-internal thread.
     */
    @JavascriptInterface
    fun onNickTap(nick: String) {
        Handler(Looper.getMainLooper()).post { callbacks.onNickTap(nick) }
    }

    @JavascriptInterface
    fun onPinnedChanged(atBottom: String) { /* reserved for a jump-to-latest affordance */ }
}

private class RendererState {
    var ready = false
    var pending: String? = null
    /** Replayed on reload so a WebView recreation keeps the chosen theme. */
    var appliedTheme: String? = null
    var appliedLayout: String? = null
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MessageWebView(
    messages: List<ChatMessage>,
    scheme: String,
    highlight: String,
    layout: NickLayout,
    modifier: Modifier = Modifier,
    callbacks: RendererCallbacks = RendererCallbacks(),
) {
    val state = remember { RendererState() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    // Bundled assets only. The renderer must not be able to read
                    // the filesystem or reach the network: every message it
                    // renders is untrusted input from a public channel.
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    blockNetworkLoads = true
                    blockNetworkImage = true
                    cacheMode = WebSettings.LOAD_NO_CACHE
                }
                addJavascriptInterface(
                    Bridge(callbacks, ready = {
                        // onReady arrives on a WebView-internal thread; all
                        // WebView calls must be made on the UI thread.
                        post {
                            state.ready = true
                            state.appliedTheme?.let { evaluateJavascript(it, null) }
                            state.appliedLayout?.let { evaluateJavascript(it, null) }
                            state.pending?.let { evaluateJavascript(it, null) }
                            state.pending = null
                        }
                    }),
                    "HcBridge",
                )
                loadUrl("file:///android_asset/renderer/index.html")
            }
        },
        update = { webView ->
            val themeCall = RendererBridge.themeCall(scheme, highlight)
            if (state.appliedTheme != themeCall) {
                state.appliedTheme = themeCall
                if (state.ready) webView.evaluateJavascript(themeCall, null)
            }
            val layoutCall = RendererBridge.layoutCall(layout)
            if (state.appliedLayout != layoutCall) {
                state.appliedLayout = layoutCall
                if (state.ready) webView.evaluateJavascript(layoutCall, null)
            }
            val call = RendererBridge.renderCall(messages)
            if (state.ready) webView.evaluateJavascript(call, null) else state.pending = call
        },
    )
}
