package io.github.hamzabhai.jhatpat.share

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hamzabhai.jhatpat.MainActivity
import io.github.hamzabhai.jhatpat.server.HostApi
import io.github.hamzabhai.jhatpat.service.SharingController
import io.github.hamzabhai.jhatpat.service.SharingState
import io.github.hamzabhai.jhatpat.ui.Card
import io.github.hamzabhai.jhatpat.ui.Cream
import io.github.hamzabhai.jhatpat.ui.Ink
import io.github.hamzabhai.jhatpat.ui.JhatPatTheme
import io.github.hamzabhai.jhatpat.ui.Muted
import io.github.hamzabhai.jhatpat.ui.Orange
import io.github.hamzabhai.jhatpat.ui.PrimaryButton
import io.github.hamzabhai.jhatpat.ui.SecondaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Share → JhatPat" from Gallery, Files, WhatsApp and other apps. */
class ShareReceiverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = ShareIntents.parse(intent)
        setContent {
            JhatPatTheme {
                Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
                    ShareScreen(
                        request = request,
                        onOpenApp = {
                            startActivity(Intent(this@ShareReceiverActivity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        },
                        onDone = { finish() },
                    )
                }
            }
        }
    }
}

private sealed interface Phase {
    data object Choosing : Phase
    data class Sending(val done: Int, val total: Int) : Phase
    data class Finished(val message: String, val problems: List<String>) : Phase
}

@Composable
private fun ShareScreen(request: ShareRequest, onOpenApp: () -> Unit, onDone: () -> Unit) {
    val state by SharingController.state.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("⚡ Send with JhatPat", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Cream)
        when {
            request.isEmpty -> Notice("Nothing to send", "The other app did not share a file or text.", "Close", onDone)
            state !is SharingState.Running -> Notice(
                "Start sharing first",
                "JhatPat is not sharing yet. Open JhatPat, tap Start sharing, then share again.",
                "Open JhatPat", onOpenApp,
            )
            (state as SharingState.Running).people == 0 -> Notice(
                "Nobody is connected yet",
                "Ask people to scan the QR code in JhatPat first. Nothing was sent.",
                "Open JhatPat", onOpenApp,
            )
            else -> Chooser(request, state as SharingState.Running, onDone)
        }
    }
}

@Composable
private fun Notice(title: String, message: String, button: String, onClick: () -> Unit) {
    Card {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(message, color = Muted)
        PrimaryButton(button, onClick = onClick)
    }
}

@Composable
private fun Chooser(request: ShareRequest, running: SharingState.Running, onDone: () -> Unit) {
    val context = LocalContext.current
    val api = remember(running.info.port, running.info.hostToken) { HostApi("http://127.0.0.1:${running.info.port}", running.info.hostToken) }
    var recipients by remember { mutableStateOf<List<HostApi.Recipient>?>(null) }
    var selected by remember { mutableIntStateOf(0) }
    var phase by remember { mutableStateOf<Phase>(Phase.Choosing) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) { recipients = withContext(Dispatchers.IO) { api.recipients() } }

    val what = when {
        request.uris.size == 1 -> "1 file"
        request.uris.isNotEmpty() -> "${request.uris.size} files"
        else -> "a message"
    }
    when (val p = phase) {
        Phase.Choosing -> Card {
            Text("Send $what to", fontWeight = FontWeight.Bold)
            val list = recipients
            if (list == null) {
                Text("Loading…", color = Muted)
            } else {
                list.forEachIndexed { index, r ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = selected == index, role = Role.RadioButton, onClick = { selected = index }).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == index, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Orange))
                        Text(r.label, modifier = Modifier.padding(start = 10.dp), fontWeight = FontWeight.Bold)
                    }
                }
                PrimaryButton("Send", onClick = {
                    val target = list[selected].target
                    scope.launch {
                        phase = Phase.Sending(0, maxOf(1, request.uris.size))
                        phase = if (request.uris.isNotEmpty()) {
                            val result = ShareUploader(api, ResolverShareSource(context.contentResolver))
                                .send(request.uris, target) { done, total -> phase = Phase.Sending(done, total) }
                            Phase.Finished(
                                if (result.failed.isEmpty()) "Sent ${result.sent} of ${request.uris.size}." else "Sent ${result.sent} of ${request.uris.size}. Some could not be sent:",
                                result.failed,
                            )
                        } else {
                            val sent = withContext(Dispatchers.IO) { api.sendText(request.text.orEmpty(), target) }
                            Phase.Finished(if (sent.isSuccess) "Sent." else "Could not send:", listOfNotNull(sent.exceptionOrNull()?.message))
                        }
                    }
                })
            }
            SecondaryButton("Cancel", onClick = onDone)
        }
        is Phase.Sending -> Card {
            Text("Sending ${p.done} of ${p.total}…", fontWeight = FontWeight.Bold)
            Text("Keep this screen open until it finishes.", color = Muted)
        }
        is Phase.Finished -> Card {
            Text(p.message, fontWeight = FontWeight.Bold)
            p.problems.forEach { Text("• $it", color = Muted) }
            PrimaryButton("Done", onClick = onDone)
        }
    }
}
