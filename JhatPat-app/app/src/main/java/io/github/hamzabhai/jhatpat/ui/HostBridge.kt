package io.github.hamzabhai.jhatpat.ui

import android.webkit.JavascriptInterface

/**
 * Exposed to the host page as `window.JhatPatApp`. Its presence tells the page it runs inside the
 * app (it hides Quit, the received-folder button and the server's file browser), and `open(id)` is
 * what the "Open" button of a file saved to the Gallery calls. WebView calls this on its own thread.
 */
class HostBridge(private val openReceived: (id: String) -> Unit) {
    @JavascriptInterface
    fun open(id: String) {
        openReceived(id)
    }
}
