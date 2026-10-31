package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.net.Reason
import io.github.hamzabhai.jhatpat.server.ServerStartException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The start/stop logic of sharing, with every Android and process detail behind an interface so it
 * can be tested on the JVM. [state] is what the UI and the notification observe.
 */
class SharingStateMachine(
    private val server: ServerRunner,
    private val hotspot: HotspotRunner,
    private val wifi: WifiSource,
    private val locks: Locks,
    val state: MutableStateFlow<SharingState> = MutableStateFlow(SharingState.Idle),
) {
    private val monitor = Any()
    private var locksHeld = false

    /** Starts sharing in [mode]. Ignored while already starting or running. */
    suspend fun start(mode: Mode) {
        synchronized(monitor) {
            if (state.value is SharingState.Starting || state.value is SharingState.Running) return
            state.value = SharingState.Starting(mode)
        }
        try {
            var hotspotInfo: HotspotResult.Started? = null
            val ip: String = when (mode) {
                Mode.Wifi -> wifi.currentWifiIp() ?: return fail("Connect to a WiFi network first, or create a hotspot instead.")
                Mode.Hotspot -> when (val result = hotspot.start()) {
                    is HotspotResult.Failed -> return fail(HotspotMessages.forReason(result.reason), result.reason)
                    is HotspotResult.Started -> result.also { hotspotInfo = it }.ip
                }
            }
            val info = server.start(ip)
            synchronized(monitor) {
                if (state.value !is SharingState.Starting) {
                    // stop() was called while the server was starting
                    server.stop()
                    hotspot.stop()
                    return
                }
                locks.acquire()
                locksHeld = true
                state.value = SharingState.Running(mode, info, hotspotInfo, people = 0, received = 0)
            }
        } catch (e: ServerStartException) {
            fail(e.message ?: "JhatPat's server could not start.")
        } catch (e: CancellationException) {
            stop()
            throw e
        }
    }

    /** Stops everything. Safe to call at any time and more than once. */
    fun stop() {
        val wasActive: Boolean
        synchronized(monitor) {
            val before = state.value
            state.value = SharingState.Idle
            wasActive = before is SharingState.Starting || before is SharingState.Running
        }
        if (!wasActive) return
        server.stop()
        hotspot.stop()
        synchronized(monitor) {
            if (locksHeld) {
                locks.release()
                locksHeld = false
            }
        }
    }

    fun updateCounts(people: Int, received: Int) = updateRunning { it.copy(people = people, received = received) }

    /** The phone's address changed (WiFi mode). */
    fun updateIp(ip: String) = updateRunning { it.copy(info = it.info.copy(ip = ip)) }

    private fun updateRunning(change: (SharingState.Running) -> SharingState.Running) {
        synchronized(monitor) {
            val running = state.value as? SharingState.Running ?: return
            state.value = change(running)
        }
    }

    private fun fail(message: String, reason: Reason? = null) {
        hotspot.stop()
        synchronized(monitor) {
            if (state.value is SharingState.Starting) state.value = SharingState.Error(message, reason)
        }
    }
}
