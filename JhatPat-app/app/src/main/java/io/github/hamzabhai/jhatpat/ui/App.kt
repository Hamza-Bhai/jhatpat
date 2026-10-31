package io.github.hamzabhai.jhatpat.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import io.github.hamzabhai.jhatpat.service.SharingController
import io.github.hamzabhai.jhatpat.service.SharingState

private enum class Screen { Connect, Chat }

/** Start while idle (or after an error), Connect and Chat while sharing. */
@Composable
fun JhatPatApp(openReceived: (String) -> Unit = {}) {
    val context = LocalContext.current
    val state by SharingController.state.collectAsState()
    val start = rememberStarter()
    var screen by rememberSaveable { mutableStateOf(Screen.Connect) }
    val running = state is SharingState.Running
    LaunchedEffect(running) { if (running) screen = Screen.Connect }

    Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        when (val s = state) {
            is SharingState.Running -> when (screen) {
                Screen.Chat -> ChatScreen(s, onBack = { screen = Screen.Connect }, openReceived = openReceived)
                Screen.Connect -> ConnectScreen(s, onOpenChat = { screen = Screen.Chat }, onStop = { SharingController.stop(context) })
            }
            else -> StartScreen(s, onStart = start)
        }
    }
}
