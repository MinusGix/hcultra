package chat.hc.ultra.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import chat.hc.ultra.data.ViewerImage
import chat.hc.ultra.data.ViewerImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
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

        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var chrome by remember { mutableStateOf(true) }
        // One action at a time: a fetch in flight must not be started twice by
        // an impatient second tap, and the buttons dim to say so.
        var busy by remember { mutableStateOf(false) }
        var pendingSave by remember { mutableStateOf<ViewerImage?>(null) }
        var confirmShare by remember { mutableStateOf(false) }

        // Android 8 and 9 save through the system's own "save as" dialog; see
        // ViewerImages.saveToPictures for why.
        val saveAs = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("image/*"),
        ) { uri ->
            val image = pendingSave
            pendingSave = null
            if (uri != null && image != null) {
                runImageAction(context, "save") { ViewerImages.writeTo(context, image, uri); "Saved" }
                    .let { toast(context, it) }
            }
        }

        /*
         * Fetch (or reuse) the file, then do [what] with it. Everything that can
         * fail — the network, a host that turns out not to serve an image,
         * storage — ends in a toast rather than a crash or a silent nothing.
         */
        fun withImage(verb: String, what: (ViewerImage) -> String?) {
            if (busy) return
            busy = true
            scope.launch {
                val message = try {
                    val image = ViewerImages.fetch(context, url)
                    what(image)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    "Couldn\u2019t $verb image: ${e.message ?: "unknown error"}"
                } finally {
                    busy = false
                }
                message?.let { toast(context, it) }
            }
        }
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
                val acting = chrome && !busy
                ViewerButton(CopyIcon, "Copy image", enabled = acting, dimmed = busy) {
                    withImage("copy") { image ->
                        val clip = ClipData.newUri(
                            context.contentResolver, "Image", ViewerImages.contentUri(context, image),
                        )
                        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
                        // From 13 the system shows its own confirmation, and a
                        // toast on top of it would say the same thing twice.
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null else "Image copied"
                    }
                }
                Spacer(Modifier.width(8.dp))
                ViewerButton(DownloadIcon, "Save image", enabled = acting, dimmed = busy) {
                    withImage("save") { image ->
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ViewerImages.saveToPictures(context, image)
                            "Saved to Pictures/hcultra"
                        } else {
                            pendingSave = image
                            saveAs.launch(image.displayName)
                            null
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                // Asked first, before anything is fetched: sharing sends the
                // picture out of the app, and the button sits right beside
                // Save and Copy, where a slipped thumb is easy.
                ViewerButton(ShareIcon, "Share image", enabled = acting, dimmed = busy) {
                    confirmShare = true
                }
                Spacer(Modifier.width(8.dp))
                ViewerButton(OpenInNew, "Open original", enabled = chrome) {
                    onOpenOriginal(url)
                    onDismiss()
                }
            }
        }

        if (confirmShare) {
            AlertDialog(
                onDismissRequest = { confirmShare = false },
                title = { Text("Share this image?") },
                text = { Text("Are you sure you want to share it with another app?") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmShare = false
                        withImage("share") { image ->
                            val uri = ViewerImages.contentUri(context, image)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = image.mime
                                putExtra(Intent.EXTRA_STREAM, uri)
                                // The clip data is what carries the read grant
                                // through the chooser to whichever app is picked.
                                clipData = ClipData.newRawUri(null, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, null))
                            null
                        }
                    }) { Text("Share") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmShare = false }) { Text("Cancel") }
                },
            )
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
    dimmed: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .alpha(if (dimmed) 0.45f else 1f)
            .size(44.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(24.dp))
    }
}

private fun toast(context: Context, message: String) =
    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

/** A one-shot action outside [withImage]'s fetch: same failure handling, no fetch. */
private inline fun runImageAction(context: Context, verb: String, action: () -> String): String =
    try {
        action()
    } catch (e: Exception) {
        "Couldn\u2019t $verb image: ${e.message ?: "unknown error"}"
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
