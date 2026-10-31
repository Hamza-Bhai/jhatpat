package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.net.Reason
import io.github.hamzabhai.jhatpat.server.StartupInfo

/** How other people reach the phone: through the WiFi it is already on, or a hotspot it creates. */
enum class Mode { Wifi, Hotspot }

sealed interface SharingState {
    data object Idle : SharingState

    data class Starting(val mode: Mode) : SharingState

    data class Running(
        val mode: Mode,
        val info: StartupInfo,
        /** The hotspot's name and password when [mode] is [Mode.Hotspot]. */
        val hotspot: HotspotResult.Started?,
        val people: Int,
        val received: Int,
    ) : SharingState

    /** [hotspotReason] is set when the hotspot could not start, so the UI can offer the fallback. */
    data class Error(val message: String, val hotspotReason: Reason? = null) : SharingState
}

/** Runs the JhatPat server. */
interface ServerRunner {
    suspend fun start(ip: String): StartupInfo
    fun stop()
}

/** Creates and releases the hotspot. */
interface HotspotRunner {
    suspend fun start(): HotspotResult
    fun stop()
}

/** The phone's current WiFi address, or null when it is not on WiFi. */
fun interface WifiSource {
    fun currentWifiIp(): String?
}

/** Keeps the phone awake and WiFi fast while sharing. */
interface Locks {
    fun acquire()
    fun release()
}
