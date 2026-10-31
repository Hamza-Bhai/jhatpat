package io.github.hamzabhai.jhatpat.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hamzabhai.jhatpat.server.HostApi
import io.github.hamzabhai.jhatpat.service.SharingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Lets a person nearby install JhatPat from this phone: a QR code for `/app.apk` that works for
 * [ShareAppUiModel.MINUTES] minutes. The link is switched off again when the countdown ends, when
 * the host taps the button, and whenever this screen is left.
 */
@Composable
fun ShareAppScreen(state: SharingState.Running, onClose: () -> Unit) {
    val api = remember(state.info.port, state.info.hostToken) { HostApi("http://127.0.0.1:${state.info.port}", state.info.hostToken) }
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var remaining by remember { mutableIntStateOf(ShareAppUiModel.MINUTES * 60) }
    val ui = ShareAppUiModel.of(state, remaining)
    BackHandler(onBack = onClose)

    LaunchedEffect(api) { enabled = withContext(Dispatchers.IO) { api.enableApk(ShareAppUiModel.MINUTES) } }
    LaunchedEffect(enabled) {
        if (enabled == true) {
            while (remaining > 0) {
                delay(1_000)
                remaining--
            }
            withContext(Dispatchers.IO) { api.disableApk() }
        }
    }
    DisposableEffect(api) { onDispose { Thread { api.disableApk() }.start() } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("📲 Share this app", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Cream)
        Card {
            when {
                enabled == false -> Text("Could not switch app sharing on. Stop sharing and start again.", color = Muted)
                enabled == null -> Text("Starting…", color = Muted)
                ui.expired -> Text("Time is up. Open this screen again to share the app again.", color = Muted)
                else -> {
                    Text("Scan with the other phone's camera", fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) { QrImage(ui.url, sizeDp = 240) }
                    }
                    Text(ui.address, color = Yellow, fontWeight = FontWeight.Bold, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    Text("Available for ${ui.countdown}", color = Muted, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
        Card {
            Text("Installing it", fontWeight = FontWeight.Bold)
            Text("When Android asks, allow \"Install unknown apps\" for the browser that downloaded the file.", color = Muted, fontSize = 13.sp)
            Text("Then open the downloaded JhatPat file and tap Install.", color = Muted, fontSize = 13.sp)
        }
        SecondaryButton("Stop sharing the app", onClick = onClose)
    }
}
