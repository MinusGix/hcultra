package chat.hc.ultra.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import chat.hc.core.render.TranscriptSync
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
    /** A tapped embedded image, by its URL: show it full screen. */
    val onImageTap: (String) -> Unit = {},
    /** A message's whole text, to go into the composer. */
    val onCompose: (String) -> Unit = {},
)

private class Bridge(
    private val context: Context,
    private val callbacks: RendererCallbacks,
    private val ready: () -> Unit,
) {
    @JavascriptInterface
    fun onReady(unused: String) = ready()

    @JavascriptInterface
    fun onLinkTap(url: String) = callbacks.onLinkTap(url)

    @JavascriptInterface
    fun onChannelTap(channel: String) = callbacks.onChannelTap(channel)

    /** Main thread for the same reason as [onNickTap]: it opens a dialog. */
    @JavascriptInterface
    fun onImageTap(url: String) {
        Handler(Looper.getMainLooper()).post { callbacks.onImageTap(url) }
    }

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

    /**
     * A message's whole text, from its Copy chip.
     *
     * Handled here rather than passed up as a callback: it needs nothing from
     * the screen, and the page has already shown "Copied", so there is no
     * further state for anyone to react to.
     */
    @JavascriptInterface
    fun onCopy(text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post {
            context.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("message", text))
        }
    }

    /** Main thread, as [onNickTap]: it edits the composer too. */
    @JavascriptInterface
    fun onCompose(text: String) {
        if (text.isEmpty()) return
        Handler(Looper.getMainLooper()).post { callbacks.onCompose(text) }
    }

    @JavascriptInterface
    fun onPinnedChanged(atBottom: String) { /* reserved for a jump-to-latest affordance */ }
}

private class RendererState {
    var ready = false

    /**
     * What the page is holding, and therefore what it still needs telling.
     *
     * Kept here rather than recreated per recomposition: it *is* the record of
     * the page's DOM, so losing it would have us patching rows the page no
     * longer has. Reset — never dropped — whenever that DOM goes away.
     */
    val sync = TranscriptSync()

    /**
     * The last thing asked for, replayed once the page is ready.
     *
     * The channel and its messages rather than the calls they produced: the
     * calls are computed against [sync], and on a reload [sync] is reset first,
     * so calls built before then would describe a page that no longer exists.
     */
    var lastChannel: String? = null
    var lastMessages: List<ChatMessage> = emptyList()
    /** Replayed on reload so a WebView recreation keeps the chosen theme. */
    var appliedTheme: String? = null
    var appliedLayout: String? = null
    var appliedImages: String? = null
    var appliedFontScale: String? = null

    /**
     * Read by the request gate, which outlives any single recomposition — the
     * `WebViewClient` is installed once, when the WebView is built, and has to
     * see the setting as it stands at request time rather than as it stood then.
     */
    var allowImages = false
}

/**
 * The only two things a renderer WebView may fetch: its own bundled assets, and
 * — when the user has turned images on — an image from one of the hosts
 * hack.chat embeds from.
 *
 * Shared by the transcript and the image viewer. [page] is the one document the
 * WebView is allowed to be showing.
 *
 * The JavaScript checks the same list before writing an `<img>` at all, so in
 * ordinary use nothing reaches here that this would refuse. It is the gate
 * regardless, because the alternative is trusting a decision made inside the
 * page that renders untrusted text: turning images on lifts a blanket
 * `blockNetworkLoads`, and this is what keeps that from meaning "the renderer
 * may now talk to anyone".
 */
internal class AssetsAndImagesOnly(
    private val page: String,
    private val allowImages: () -> Boolean,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith(ASSET_PREFIX)) return null
        if (allowImages() && ImageHosts.allows(url)) return null
        // A response with no body: the load fails, nothing else does.
        return WebResourceResponse("text/plain", "utf-8", null)
    }

    /**
     * Nothing ever navigates this WebView. Taps are handed to native by the page
     * itself; this catches anything that is not a tap — a redirect served in
     * place of an image, say.
     */
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        request.url.toString() != "$ASSET_PREFIX$page"

    companion object {
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
internal fun WebView.applyNetworkPolicy(allowImages: Boolean) {
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
    /** Which channel [messages] belongs to; the page keeps one container each. */
    channel: String,
    messages: List<ChatMessage>,
    scheme: String,
    highlight: String,
    layout: NickLayout,
    /** Embed images from hack.chat's whitelisted hosts rather than link them. */
    allowImages: Boolean,
    /** Multiplier on the page's base text size; see [chat.hc.core.render.FontScale]. */
    fontScale: Float,
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
                webViewClient = AssetsAndImagesOnly("index.html") { state.allowImages }
                applyNetworkPolicy(allowImages)
                addJavascriptInterface(
                    Bridge(context, callbacks, ready = {
                        // onReady arrives on a WebView-internal thread; all
                        // WebView calls must be made on the UI thread.
                        post {
                            state.ready = true
                            state.appliedTheme?.let { evaluateJavascript(it, null) }
                            state.appliedLayout?.let { evaluateJavascript(it, null) }
                            state.appliedFontScale?.let { evaluateJavascript(it, null) }
                            // Before the messages: the page drops every
                            // container when this changes, and there is nothing
                            // to drop yet.
                            state.appliedImages?.let { evaluateJavascript(it, null) }
                            // A fresh page holds nothing, whatever we last
                            // believed — this is a first load or a reload, and
                            // the second is exactly the case where a stale
                            // record would have us patching rows that are gone.
                            state.sync.reset()
                            state.lastChannel?.let { ch ->
                                state.sync.update(ch, state.lastMessages)
                                    .forEach { evaluateJavascript(it, null) }
                            }
                        }
                    }),
                    "HcBridge",
                )
                loadUrl("${AssetsAndImagesOnly.ASSET_PREFIX}index.html")
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
            val fontCall = RendererBridge.fontScaleCall(fontScale)
            if (state.appliedFontScale != fontCall) {
                state.appliedFontScale = fontCall
                if (state.ready) webView.evaluateJavascript(fontCall, null)
            }
            val imagesCall = RendererBridge.allowImagesCall(allowImages)
            if (state.appliedImages != imagesCall) {
                state.appliedImages = imagesCall
                // The gate first: the images the page writes are requested the
                // moment it redraws, and it must not do that through a closed
                // network policy.
                state.allowImages = allowImages
                webView.applyNetworkPolicy(allowImages)
                if (state.ready) webView.evaluateJavascript(imagesCall, null)
                // The page answers that call by dropping every container, since
                // whether a message shows an image is not part of the message.
                // Both sides forget together or neither does.
                state.sync.reset()
            }

            // Held for the reload path, which recomputes from these rather than
            // from calls built against a record that reset() has since cleared.
            state.lastChannel = channel
            state.lastMessages = messages
            if (state.ready) {
                state.sync.update(channel, messages)
                    .forEach { webView.evaluateJavascript(it, null) }
            }
        },
    )
}
