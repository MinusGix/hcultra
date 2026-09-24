package chat.hc.ultra.ui

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import org.json.JSONObject

/**
 * An embedded image, full screen, without leaving the app.
 *
 * Opening the image in a browser meant switching apps for a closer look, and
 * landing on whatever tab the browser had been showing when you came back. Here
 * it fits the screen, zooms and pans, and goes away with back, the back
 * gesture, the button, or a swipe; the original is one button away for when a
 * browser really is what you want.
 *
 * The image is shown by a WebView rather than decoded natively. That is what
 * the transcript already fetched it with, so it comes out of the same HTTP cache
 * rather than over the network a second time, animated GIFs play without a
 * decoder of our own, and the fetch goes through the same [AssetsAndImagesOnly]
 * gate — the viewer can reach exactly what the transcript could, and nothing
 * more. The gestures live in `assets/renderer/viewer.js`.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ImageViewer(
    url: String,
    onDismiss: () -> Unit,
    onOpenOriginal: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            // Edge to edge: the image may use the whole screen, and the buttons
            // inset themselves below.
            decorFitsSystemWindows = false,
        ),
    ) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            window?.let {
                it.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                // The page paints its own black, and fades it while the image
                // is swiped away; a dim from the window would sit under that
                // and keep the transcript dark.
                it.setDimAmount(0f)
                // White status icons, whatever the scheme: they sit over black.
                WindowCompat.getInsetsController(it, it.decorView).isAppearanceLightStatusBars = false
            }
        }

        var chrome by remember { mutableStateOf(true) }
        val chromeAlpha by animateFloatAsState(if (chrome) 1f else 0f, label = "chrome")
        val dismiss by rememberUpdatedState(onDismiss)

        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        settings.apply {
                            javaScriptEnabled = true
                            allowFileAccess = false
                            allowContentAccess = false
                            domStorageEnabled = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            setSupportZoom(false)
                        }
                        // The viewer is only reachable from an embedded image,
                        // which only exists with images turned on.
                        webViewClient = AssetsAndImagesOnly("viewer.html") { true }
                        applyNetworkPolicy(allowImages = true)
                        addJavascriptInterface(
                            ViewerBridge(
                                onReady = {
                                    evaluateJavascript("HCV.open(${JSONObject.quote(url)});", null)
                                },
                                onTap = { chrome = !chrome },
                                onDismiss = { dismiss() },
                            ),
                            "HcViewer",
                        )
                        loadUrl("${AssetsAndImagesOnly.ASSET_PREFIX}viewer.html")
                    }
                },
                onRelease = { it.destroy() },
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp)
                    .alpha(chromeAlpha),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ViewerButton(BackArrow, "Back", enabled = chrome, onClick = onDismiss)
                Box(Modifier.weight(1f))
                ViewerButton(OpenInNew, "Open original", enabled = chrome) {
                    onOpenOriginal(url)
                    onDismiss()
                }
            }
        }
    }
}

/**
 * Round, translucent, white on black: legible over any image, and it is always
 * over an image, so the app's scheme colours have no business here.
 */
@Composable
private fun ViewerButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

/** Calls from the page arrive on a WebView thread; everything here touches UI. */
private class ViewerBridge(
    private val onReady: () -> Unit,
    private val onTap: () -> Unit,
    private val onDismiss: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady(unused: String) { main.post(onReady) }

    @JavascriptInterface
    fun onTap(unused: String) { main.post(onTap) }

    @JavascriptInterface
    fun onDismiss(unused: String) { main.post(onDismiss) }
}

/*
 * The two icons, as path data from Material's `arrow_back` and `open_in_new`.
 * Drawn here rather than pulling in the icons artifact for two glyphs.
 */
private fun icon(name: String, path: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()

private val BackArrow = icon(
    "BackArrow",
    "M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z",
)

private val OpenInNew = icon(
    "OpenInNew",
    "M19,19H5V5h7V3H5c-1.11,0 -2,0.9 -2,2v14c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2v-7h-2v7z" +
        "M14,3v2h3.59l-9.83,9.83 1.41,1.41L19,6.41V10h2V3h-7z",
)
