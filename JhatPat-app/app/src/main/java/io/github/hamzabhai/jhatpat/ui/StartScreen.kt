package io.github.hamzabhai.jhatpat.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hamzabhai.jhatpat.net.Reason
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingState

@Composable
fun StartScreen(state: SharingState, onStart: (Mode) -> Unit) {
    val context = LocalContext.current
    var mode by rememberSaveable { mutableStateOf(Mode.Wifi) }
    val starting = state is SharingState.Starting

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("⚡ JhatPat", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Cream)
        Text("Share files with phones and laptops nearby. No internet needed.", color = Muted)

        if (state is SharingState.Error) ErrorCard(state, onStart, context)

        Card {
            Text("How should others connect?", fontWeight = FontWeight.Bold)
            ModeRow("Use my current WiFi", "Everyone joins the WiFi this phone is on.", mode == Mode.Wifi) { mode = Mode.Wifi }
            ModeRow("Create a hotspot", "No WiFi around? Others join a hotspot JhatPat creates. It has no internet.", mode == Mode.Hotspot) { mode = Mode.Hotspot }
        }

        PrimaryButton(if (starting) "Starting…" else "▶ Start sharing", onClick = { onStart(mode) }, enabled = !starting)
        if (mode == Mode.Hotspot) {
            Text("A hotspot needs the Location permission once (an Android rule).", color = Muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModeRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Orange))
        Column(Modifier.padding(start = 10.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Muted, fontSize = 13.sp)
        }
    }
}

/** Shows why sharing could not start, with the fallback when the hotspot is the problem. */
@Composable
private fun ErrorCard(error: SharingState.Error, onStart: (Mode) -> Unit, context: Context) {
    Card {
        Text("😟 " + error.message, color = MaterialTheme.colorScheme.error)
        when (error.hotspotReason) {
            Reason.Busy, Reason.Unsupported, Reason.Unknown -> {
                SecondaryButton("Open hotspot settings", onClick = { openHotspotSettings(context) })
                PrimaryButton("Continue with my current WiFi", onClick = { onStart(Mode.Wifi) })
            }
            else -> SecondaryButton("Try again", onClick = { onStart(if (error.hotspotReason != null) Mode.Hotspot else Mode.Wifi) })
        }
    }
}

private fun openHotspotSettings(context: Context) {
    val tether = Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(tether)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // no settings screen to open
        }
    }
}
