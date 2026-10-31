package io.github.hamzabhai.jhatpat.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.hamzabhai.jhatpat.net.HotspotLogic
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingController

/**
 * Returns the function the Start button calls. It asks for the permissions the chosen mode needs
 * at that moment (the hotspot permission only for a hotspot, notifications on Android 13+), then
 * starts sharing. If a permission is refused, sharing still starts and the hotspot step explains
 * what is missing.
 */
@Composable
fun rememberStarter(): (Mode) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Mode?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pending?.let { SharingController.start(context, it) }
        pending = null
    }
    return { mode ->
        val wanted = buildList {
            if (mode == Mode.Hotspot) addAll(HotspotLogic.requiredPermissions(Build.VERSION.SDK_INT))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            // Android 8 and 9 save received files straight into the public folders.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = wanted.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            SharingController.start(context, mode)
        } else {
            pending = mode
            launcher.launch(missing.toTypedArray())
        }
    }
}
