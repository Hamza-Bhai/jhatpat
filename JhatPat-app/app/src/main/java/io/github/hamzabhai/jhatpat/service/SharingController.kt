package io.github.hamzabhai.jhatpat.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the UI uses to start and stop sharing and to watch it. The work happens in [SharingService]. */
object SharingController {
    internal val mutableState = MutableStateFlow<SharingState>(SharingState.Idle)

    val state: StateFlow<SharingState> = mutableState

    /** Call from a visible screen: Android only allows creating a hotspot while the app is in the foreground. */
    fun start(context: Context, mode: Mode) {
        val intent = Intent(context, SharingService::class.java)
            .setAction(SharingService.ACTION_START)
            .putExtra(SharingService.EXTRA_MODE, mode.name)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, SharingService::class.java).setAction(SharingService.ACTION_STOP))
    }
}
