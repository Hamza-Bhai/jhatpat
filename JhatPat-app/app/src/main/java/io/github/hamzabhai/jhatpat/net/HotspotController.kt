package io.github.hamzabhai.jhatpat.net

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Starts and stops a local-only hotspot (no internet; random name and password chosen by Android).
 * The decisions that need no Android live in [HotspotLogic].
 */
class HotspotController(
    context: Context,
    private val network: NetworkInfoProvider = SystemNetworkInfo,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) {
    private val app = context.applicationContext
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager

    @Volatile
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    fun requiredPermissions(): List<String> = HotspotLogic.requiredPermissions(sdkInt)

    /** The phone's own WiFi address, for the "use my current WiFi" mode. Null when not connected. */
    fun currentWifiIp(): String? = WifiClientAddress.pick(network.candidates())

    @SuppressLint("MissingPermission")
    suspend fun start(): HotspotResult {
        val missing = requiredPermissions().filter { ContextCompat.checkSelfPermission(app, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "hotspot needs permissions: $missing")
            return HotspotResult.Failed(Reason.NoPermission)
        }
        if (HotspotLogic.needsLocationServices(sdkInt) && !locationIsOn()) {
            return HotspotResult.Failed(Reason.LocationOff)
        }
        stop()
        val started = when (val request = requestHotspot()) {
            is Refused -> return request.failure
            is Granted -> request
        }
        // The hotspot's interface can take a moment to get its address.
        var result: HotspotResult = HotspotLogic.result(started.ssid, started.password, network.candidates())
        var tries = 0
        while (result is HotspotResult.Failed && tries++ < ADDRESS_RETRIES) {
            delay(ADDRESS_RETRY_MS)
            result = HotspotLogic.result(started.ssid, started.password, network.candidates())
        }
        if (result is HotspotResult.Failed) stop()
        return result
    }

    /** Releases the hotspot. Safe to call when it is not running. */
    fun stop() {
        val r = reservation ?: return
        reservation = null
        try {
            r.close()
        } catch (_: Exception) {
            // already released
        }
    }

    private sealed interface Request
    private class Granted(val ssid: String, val password: String) : Request
    private class Refused(val failure: HotspotResult.Failed) : Request

    @SuppressLint("MissingPermission")
    private suspend fun requestHotspot(): Request = suspendCancellableCoroutine { cont ->
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                reservation = res
                val (ssid, password) = credentials(res)
                if (cont.isActive) cont.resume(Granted(ssid, password)) else stop()
            }

            override fun onFailed(reason: Int) {
                Log.w(TAG, "hotspot failed, code $reason")
                if (cont.isActive) cont.resume(Refused(HotspotResult.Failed(HotspotLogic.reasonFor(reason))))
            }
        }
        try {
            wifi.startLocalOnlyHotspot(callback, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            Log.w(TAG, "hotspot refused by Android: ${e.message}")
            if (cont.isActive) cont.resume(Refused(HotspotResult.Failed(Reason.NoPermission)))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "hotspot cannot start now: ${e.message}")
            if (cont.isActive) cont.resume(Refused(HotspotResult.Failed(Reason.Busy)))
        }
        cont.invokeOnCancellation { stop() }
    }

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String> {
        if (sdkInt >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            return HotspotLogic.unquote(config.ssid) to HotspotLogic.unquote(config.passphrase)
        }
        val config = res.wifiConfiguration
        return HotspotLogic.unquote(config?.SSID) to HotspotLogic.unquote(config?.preSharedKey)
    }

    private fun locationIsOn(): Boolean {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    private companion object {
        const val TAG = "JhatPatHotspot"
        const val ADDRESS_RETRIES = 10
        const val ADDRESS_RETRY_MS = 300L
    }
}
