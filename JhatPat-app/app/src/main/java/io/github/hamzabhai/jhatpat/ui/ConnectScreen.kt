package io.github.hamzabhai.jhatpat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hamzabhai.jhatpat.service.NotificationText
import io.github.hamzabhai.jhatpat.service.SharingState

/** Shows one QR at a time (see [ConnectUiModel]) plus the way into the chat and the Stop button. */
@Composable
fun ConnectScreen(state: SharingState.Running, onOpenChat: () -> Unit, onStop: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val ui = ConnectUiModel.of(state, step)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("⚡ Sharing", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Cream)
            Text(NotificationText.body(state), color = Muted, fontSize = 13.sp)
        }

        Card {
            Text(ui.title, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White)) {
                    QrImage(ui.qrText, sizeDp = 240)
                }
            }
            if (ui.showPin) {
                Text(
                    ui.pin.toList().joinToString(" "),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Black,
                    color = Yellow,
                )
            }
            Text(ui.caption, color = Muted, fontSize = 13.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            ui.nextButton?.let { PrimaryButton(it, onClick = { step = 1 }) }
            if (ui.hasBack) SecondaryButton("Back", onClick = { step = 0 })
        }

        PrimaryButton("💬 Open chat", onClick = onOpenChat)
        SecondaryButton("■ Stop sharing", onClick = onStop)
    }
}
