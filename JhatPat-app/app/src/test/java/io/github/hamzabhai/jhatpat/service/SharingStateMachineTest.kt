package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.net.Reason
import io.github.hamzabhai.jhatpat.server.ServerStartException
import io.github.hamzabhai.jhatpat.server.StartupInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingStateMachineTest {
    private val info = StartupInfo(8787, "tok", "1234", "sec", "192.168.1.5", "http://localhost:8787/host?t=tok")
    private val started = HotspotResult.Started("AndroidShare_1", "pw", "192.168.43.1")

    private class FakeServer(val info: StartupInfo, var failWith: ServerStartException? = null, var gate: CompletableDeferred<Unit>? = null) : ServerRunner {
        var startedWithIp: String? = null
        var starts = 0
        var stops = 0
        override suspend fun start(ip: String): StartupInfo {
            starts++
            gate?.await()
            failWith?.let { throw it }
            startedWithIp = ip
            return info.copy(ip = ip)
        }

        override fun stop() { stops++ }
    }

    private class FakeHotspot(var result: HotspotResult) : HotspotRunner {
        var starts = 0
        var stops = 0
        override suspend fun start(): HotspotResult { starts++; return result }
        override fun stop() { stops++ }
    }

    private class FakeLocks : Locks {
        var acquires = 0
        var releases = 0
        override fun acquire() { acquires++ }
        override fun release() { releases++ }
    }

    private class Rig(val server: FakeServer, val hotspot: FakeHotspot, val locks: FakeLocks, wifiIp: String?) {
        val machine = SharingStateMachine(server, hotspot, { wifiIp }, locks)
        val state get() = machine.state.value
    }

    private fun rig(wifiIp: String? = "192.168.1.5", hotspot: HotspotResult = started, server: FakeServer = FakeServer(info)) =
        Rig(server, FakeHotspot(hotspot), FakeLocks(), wifiIp)

    @Test
    fun startWifiGoesRunning() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Wifi)
        assertEquals(SharingState.Running(Mode.Wifi, info.copy(ip = "192.168.1.5"), null, 0, 0), r.state)
        assertEquals("192.168.1.5", r.server.startedWithIp)
        assertEquals(0, r.hotspot.starts)
        assertEquals(1, r.locks.acquires)
    }

    @Test
    fun startHotspotGoesRunningWithTheHotspotDetails() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Hotspot)
        val s = r.state as SharingState.Running
        assertEquals(started, s.hotspot)
        assertEquals("192.168.43.1", s.info.ip)
        assertEquals("192.168.43.1", r.server.startedWithIp)
    }

    @Test
    fun hotspotFailureGoesToErrorWithItsReason() = runBlocking {
        val r = rig(hotspot = HotspotResult.Failed(Reason.Busy))
        r.machine.start(Mode.Hotspot)
        val s = r.state as SharingState.Error
        assertEquals(Reason.Busy, s.hotspotReason)
        assertTrue(s.message, s.message.contains("Hotspot"))
        assertEquals(0, r.server.starts)
        assertEquals(0, r.locks.acquires)
    }

    @Test
    fun serverStartFailureGoesToErrorAndReleasesTheHotspot() = runBlocking {
        val r = rig(server = FakeServer(info, failWith = ServerStartException("port busy")))
        r.machine.start(Mode.Hotspot)
        assertEquals(SharingState.Error("port busy", null), r.state)
        assertEquals(1, r.hotspot.stops)
        assertEquals(0, r.locks.acquires)
    }

    @Test
    fun wifiModeWithoutWifiIsAnError() = runBlocking {
        val r = rig(wifiIp = null)
        r.machine.start(Mode.Wifi)
        val s = r.state as SharingState.Error
        assertTrue(s.message, s.message.contains("WiFi"))
        assertEquals(0, r.server.starts)
    }

    @Test
    fun stopFromRunningReleasesEverything() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Hotspot)
        r.machine.stop()
        assertEquals(SharingState.Idle, r.state)
        assertEquals(1, r.server.stops)
        assertEquals(1, r.hotspot.stops)
        assertEquals(1, r.locks.releases)
    }

    @Test
    fun stopIsIdempotent() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Hotspot)
        r.machine.stop()
        r.machine.stop()
        assertEquals(1, r.server.stops)
        assertEquals(1, r.hotspot.stops)
        assertEquals(1, r.locks.releases)
    }

    @Test
    fun stopWhenIdleDoesNothing() {
        val r = rig()
        r.machine.stop()
        assertEquals(SharingState.Idle, r.state)
        assertEquals(0, r.server.stops)
        assertEquals(0, r.locks.releases)
    }

    @Test
    fun startWhileRunningIsIgnored() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Wifi)
        r.machine.start(Mode.Hotspot)
        assertEquals(1, r.server.starts)
        assertEquals(Mode.Wifi, (r.state as SharingState.Running).mode)
    }

    @Test
    fun canStartAgainAfterAnError() = runBlocking {
        val r = rig(wifiIp = null)
        r.machine.start(Mode.Wifi)
        assertTrue(r.state is SharingState.Error)
        r.machine.start(Mode.Hotspot)
        assertTrue(r.state is SharingState.Running)
    }

    @Test
    fun countsAreUpdatedOnlyWhileRunning() = runBlocking {
        val r = rig()
        r.machine.updateCounts(3, 2)
        assertEquals(SharingState.Idle, r.state)
        r.machine.start(Mode.Wifi)
        r.machine.updateCounts(3, 2)
        val s = r.state as SharingState.Running
        assertEquals(3, s.people)
        assertEquals(2, s.received)
    }

    @Test
    fun updateIpChangesTheRunningAddress() = runBlocking {
        val r = rig()
        r.machine.start(Mode.Wifi)
        r.machine.updateIp("192.168.1.9")
        assertEquals("192.168.1.9", (r.state as SharingState.Running).info.ip)
        r.machine.stop()
        r.machine.updateIp("10.0.0.1")
        assertEquals(SharingState.Idle, r.state)
    }

    @Test
    fun stoppingWhileStartingCancelsTheStart() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val r = rig(server = FakeServer(info, gate = gate))
        val job = launch { r.machine.start(Mode.Wifi) }
        while (r.server.starts == 0) kotlinx.coroutines.yield()
        r.machine.stop()
        gate.complete(Unit)
        job.join()
        assertEquals(SharingState.Idle, r.state)
        assertEquals(0, r.locks.acquires)
        assertTrue("server must be stopped", r.server.stops >= 1)
        assertNull((r.state as? SharingState.Running))
    }
}
