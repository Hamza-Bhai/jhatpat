package io.github.hamzabhai.jhatpat.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hamzabhai.jhatpat.media.ContentResolverMedia
import io.github.hamzabhai.jhatpat.media.HostItemUrl
import io.github.hamzabhai.jhatpat.media.MovedMediaResponder
import io.github.hamzabhai.jhatpat.service.SharingController
import io.github.hamzabhai.jhatpat.service.SharingState

/**
 * The existing host page in a WebView, so the phone gets every feature and fix of the laptop page.
 * The page recognises the app through `window.JhatPatApp` (see [HostBridge]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ChatScreen(state: SharingState.Running, onBack: () -> Unit, openReceived: (String) -> Unit) {
    val context = LocalContext.current
    var chooser by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        chooser?.onReceiveValue(uris.toTypedArray())
        chooser = null
    }
    BackHandler(onBack = onBack)

    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        WebView.setWebContentsDebuggingEnabled(true)
    }
    // Files moved to the Gallery are no longer on the server, so previews of them are answered from the Gallery.
    val movedMedia = remember { MovedMediaResponder(ContentResolverMedia(context.contentResolver, SharingController.savedUris)) }
    val url = "http://127.0.0.1:${state.info.port}/host?t=${state.info.hostToken}"
    var webView by remember { mutableStateOf<WebView?>(null) }
    DisposableEffect(Unit) { onDispose { webView?.destroy() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                addJavascriptInterface(HostBridge(openReceived, onBack), "JhatPatApp")
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        if (request.method != "GET" || request.url.host != "127.0.0.1") return null
                        val item = HostItemUrl.parse(request.url.path.orEmpty()) ?: return null
                        val reply = movedMedia.respond(item.id, request.requestHeaders["Range"]) ?: return null
                        return WebResourceResponse(reply.mime, null, reply.status, reply.reason, reply.headers, reply.body)
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (request.url.host == "127.0.0.1") return false
                        // links people send in the chat open in their browser, not inside the app
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                        chooser?.onReceiveValue(null)
                        chooser = callback
                        picker.launch("*/*")
                        return true
                    }
                }
                loadUrl(url)
                webView = this
            }
        },
    )
}
