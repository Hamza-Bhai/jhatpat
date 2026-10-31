package io.github.hamzabhai.jhatpat.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import io.github.hamzabhai.jhatpat.net.HotspotController
import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.server.ServerProcess
import io.github.hamzabhai.jhatpat.server.StartupInfo
import java.io.File

/** Runs the bundled Go server from the native library directory (see the buildGoServer Gradle task). */
class RealServerRunner(private val context: Context) : ServerRunner {
    @Volatile
    private var process: ServerProcess? = null

    override suspend fun start(ip: String): StartupInfo {
        val files = context.filesDir
        val binary = File(context.applicationInfo.nativeLibraryDir, "libjhatpat.so")
        val args = listOf(
            "-json-startup", "-exit-on-stdin-close", "-no-browser", "-no-doctor",
            "-host-name", "Phone", "-host-kind", "phone",
            "-ip", ip,
            "-dir", File(files, "recv").path,
            "-config-dir", File(files, "cfg").path,
            "-apk", context.applicationInfo.sourceDir,
        )
        val server = ServerProcess(binary, args, mapOf("HOME" to files.path))
        process = server
        return server.start()
    }

    override fun stop() {
        process?.stop()
    }
}

class HotspotControllerRunner(private val controller: HotspotController) : HotspotRunner {
    override suspend fun start(): HotspotResult = controller.start()

    override fun stop() = controller.stop()
}

/** A partial wake lock and a fast WiFi lock, held only while sharing. */
class AndroidLocks(context: Context) : Locks {
    private val app = context.applicationContext
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    override fun acquire() {
        if (wakeLock == null) {
            val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JhatPat:sharing").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wifi.createWifiLock(mode, "JhatPat:sharing").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    @Synchronized
    override fun release() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }
}
