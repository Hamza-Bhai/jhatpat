package io.github.hamzabhai.jhatpat.net

import io.github.hamzabhai.jhatpat.logic.HotspotAddress

sealed interface HotspotResult {
    data class Started(val ssid: String, val password: String, val ip: String) : HotspotResult
    data class Failed(val reason: Reason) : HotspotResult
}

enum class Reason { NoPermission, LocationOff, Unsupported, Busy, Unknown }

/** The decisions of [HotspotController] that do not need Android, so they can be unit tested. */
object HotspotLogic {
    private const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
    private const val NEARBY_WIFI_DEVICES = "android.permission.NEARBY_WIFI_DEVICES"
    private const val ANDROID_13 = 33

    // LocalOnlyHotspotCallback failure codes.
    private const val ERROR_NO_CHANNEL = 1
    private const val ERROR_GENERIC = 2
    private const val ERROR_INCOMPATIBLE_MODE = 3
    private const val ERROR_TETHERING_DISALLOWED = 4

    fun requiredPermissions(sdkInt: Int): List<String> =
        if (sdkInt >= ANDROID_13) listOf(NEARBY_WIFI_DEVICES) else listOf(FINE_LOCATION)

    /** Before Android 13 the hotspot API also needs the Location switch to be on. */
    fun needsLocationServices(sdkInt: Int): Boolean = sdkInt < ANDROID_13

    fun reasonFor(code: Int): Reason = when (code) {
        ERROR_NO_CHANNEL, ERROR_INCOMPATIBLE_MODE -> Reason.Busy
        ERROR_TETHERING_DISALLOWED -> Reason.Unsupported
        ERROR_GENERIC -> Reason.Unknown
        else -> Reason.Unknown
    }

    /** The hotspot's address is the hotspot interface among [candidates] (interface name to IPv4). */
    fun result(ssid: String, password: String, candidates: List<Pair<String, String>>): HotspotResult {
        val ip = HotspotAddress.pick(candidates) ?: return HotspotResult.Failed(Reason.Unknown)
        return HotspotResult.Started(ssid, password, ip)
    }

    /** Old WifiConfiguration values come wrapped in double quotes. */
    fun unquote(value: String?): String {
        val v = value.orEmpty()
        return if (v.length >= 2 && v.startsWith('"') && v.endsWith('"')) v.substring(1, v.length - 1) else v
    }
}

/** Picks the address of the phone's own WiFi (or Ethernet) connection, for "use my current WiFi". */
object WifiClientAddress {
    private val preferredNames = listOf("wlan0", "eth0")

    fun pick(candidates: List<Pair<String, String>>): String? {
        val usable = candidates.filter { HotspotAddress.isPrivateIpv4(it.second) }
        for (name in preferredNames) {
            usable.firstOrNull { it.first == name }?.let { return it.second }
        }
        return null
    }
}
