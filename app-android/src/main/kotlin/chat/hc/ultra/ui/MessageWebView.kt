package chat.hc.ultra.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import chat.hc.core.render.ImageHosts
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
    var appliedImages: String? = null

    /**
     * Read by the request gate, which outlives any single recomposition — the
     * `WebViewClient` is installed once, when the WebView is built, and has to
     * see the setting as it stands at request time rather than as it stood then.
     */
    var allowImages = false
}

/**
 * The only two things this WebView may fetch: its own bundled assets, and — when
 * the user has turned images on — an image from one of the hosts hack.chat
 * embeds from.
 *
 * The JavaScript checks the same list before writing an `<img>` at all, so in
 * ordinary use nothing reaches here that this would refuse. It is the gate
 * regardless, because the alternative is trusting a decision made inside the
 * page that renders untrusted text: turning images on lifts a blanket
 * `blockNetworkLoads`, and this is what keeps that from meaning "the renderer
 * may now talk to anyone".
 */
private class AssetsAndImagesOnly(private val state: RendererState) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith(ASSET_PREFIX)) return null
        if (state.allowImages && ImageHosts.allows(url)) return null
        // A response with no body: the load fails, nothing else does.
        return WebResourceResponse("text/plain", "utf-8", null)
    }

    /**
     * Nothing ever navigates this WebView. Taps are handed to native by the page
     * itself; this catches anything that is not a tap — a redirect served in
     * place of an image, say.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.toString() != "${ASSET_PREFIX}index.html"

    private companion object {
        const val ASSET_PREFIX = "file:///android_asset/renderer/"
    }
}

/**
 * Whether this WebView may touch the network at all.
 *
 * Both flags, and the order is not arbitrary: blocking loads blocks images with
 * them, but *unblocking* loads does not unblock images again. So images are
 * cleared first on the way up, and the blanket flag set first on the way down —
 * neither direction leaves a gap.
 *
 * The cache follows the same switch. Bundled assets are unaffected either way
 * (they come off disk), but an image wants caching: refetching the same picture
 * on every rotation costs the user data and tells its host each time.
 */
private fun WebView.applyNetworkPolicy(allowImages: Boolean) {
    settings.apply {
        if (allowImages) {
            blockNetworkImage = false
            blockNetworkLoads = false
            cacheMode = WebSettings.LOAD_DEFAULT
        } else {
            blockNetworkLoads = true
            blockNetworkImage = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MessageWebView(
    messages: List<ChatMessage>,
    scheme: String,
    highlight: String,
    layout: NickLayout,
    /** Embed images from hack.chat's whitelisted hosts rather than link them. */
    allowImages: Boolean,
    modifier: Modifier = Modifier,
    callbacks: RendererCallbacks = RendererCallbacks(),
) {
    val state = remember { RendererState() }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            state.allowImages = allowImages
            WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.apply {
                    javaScriptEnabled = true
                    // Bundled assets only. The renderer must not be able to read
                    // the filesystem or reach the network: every message it
                    // renders is untrusted input from a public channel. Images,
                    // when the user turns them on, are the single exception, and
                    // [AssetsAndImagesOnly] is what keeps it to that.
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    // An image an https URL cannot be reached over is simply an
                    // image that does not load; it must not become a cleartext
                    // fetch announcing what you are reading.
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }
                webViewClient = AssetsAndImagesOnly(state)
                applyNetworkPolicy(allowImages)
                addJavascriptInterface(
                    Bridge(callbacks, ready = {
                        // onReady arrives on a WebView-internal thread; all
                        // WebView calls must be made on the UI thread.
                        post {
                            state.ready = true
                            state.appliedTheme?.let { evaluateJavascript(it, null) }
                            state.appliedLayout?.let { evaluateJavascript(it, null) }
                            // Before the messages: the page rebuilds the
                            // transcript when this changes, and there is nothing
                            // to rebuild yet.
                            state.appliedImages?.let { evaluateJavascript(it, null) }
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
            val imagesCall = RendererBridge.allowImagesCall(allowImages)
            if (state.appliedImages != imagesCall) {
                state.appliedImages = imagesCall
                // The gate first: the page rebuilds on this call and the images
                // it writes are requested immediately.
                state.allowImages = allowImages
                webView.applyNetworkPolicy(allowImages)
                if (state.ready) webView.evaluateJavascript(imagesCall, null)
            }
            val call = RendererBridge.renderCall(messages)
            if (state.ready) webView.evaluateJavascript(call, null) else state.pending = call
        },
    )
}
