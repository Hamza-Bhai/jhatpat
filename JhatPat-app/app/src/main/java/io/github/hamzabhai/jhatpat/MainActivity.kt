package io.github.hamzabhai.jhatpat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.hamzabhai.jhatpat.service.SharingController
import io.github.hamzabhai.jhatpat.ui.JhatPatApp
import io.github.hamzabhai.jhatpat.ui.JhatPatTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            JhatPatTheme {
                // The page calls this from a WebView thread.
                JhatPatApp(openReceived = { id -> runOnUiThread { SharingController.openReceived(this@MainActivity, id) } })
            }
        }
    }
}
