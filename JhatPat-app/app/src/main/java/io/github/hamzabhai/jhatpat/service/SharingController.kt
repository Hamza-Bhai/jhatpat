package io.github.hamzabhai.jhatpat.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/** What the UI uses to start and stop sharing and to watch it. The work happens in [SharingService]. */
object SharingController {
    internal val mutableState = MutableStateFlow<SharingState>(SharingState.Idle)

    val state: StateFlow<SharingState> = mutableState

    /** Chat item id to the URI of the copy saved to the Gallery or Downloads (this run only). */
    internal val savedUris = ConcurrentHashMap<String, String>()

    /** The chat's "Open" button for a file saved to the Gallery. Call on the main thread. */
    fun openReceived(context: Context, id: String) {
        val text = savedUris[id]
        if (text == null) {
            Toast.makeText(context, "That file is no longer available.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = Uri.parse(text)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file.", Toast.LENGTH_SHORT).show()
        }
    }

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
