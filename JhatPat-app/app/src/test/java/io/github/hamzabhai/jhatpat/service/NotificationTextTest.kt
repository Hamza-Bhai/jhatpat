package io.github.hamzabhai.jhatpat.service

import io.github.hamzabhai.jhatpat.net.Reason
import io.github.hamzabhai.jhatpat.server.StartupInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private val info = StartupInfo(8787, "t", "1234", "s", "192.168.1.5", "u")

    private fun running(people: Int, received: Int) = SharingState.Running(Mode.Wifi, info, null, people, received)

    @Test
    fun runningShowsPeopleAndFiles() {
        assertEquals("JhatPat is sharing", NotificationText.title(running(3, 2)))
        assertEquals("3 connected · 2 files received", NotificationText.body(running(3, 2)))
    }

    @Test
    fun singularForms() {
        assertEquals("1 connected · 1 file received", NotificationText.body(running(1, 1)))
        assertEquals("0 connected · 0 files received", NotificationText.body(running(0, 0)))
    }

    @Test
    fun startingAndErrorAndIdle() {
        assertEquals("Starting JhatPat…", NotificationText.title(SharingState.Starting(Mode.Hotspot)))
        assertEquals("JhatPat stopped", NotificationText.title(SharingState.Error("port busy", null)))
        assertEquals("port busy", NotificationText.body(SharingState.Error("port busy", null)))
        assertEquals("JhatPat", NotificationText.title(SharingState.Idle))
    }

    @Test
    fun everyHotspotReasonHasAHelpfulMessage() {
        for (reason in Reason.values()) {
            assertTrue(reason.name, HotspotMessages.forReason(reason).length > 20)
        }
        assertTrue(HotspotMessages.forReason(Reason.Busy).contains("Hotspot"))
        assertTrue(HotspotMessages.forReason(Reason.LocationOff).contains("Location"))
        assertTrue(HotspotMessages.forReason(Reason.NoPermission).contains("permission"))
    }

    @Test
    fun aStartCommandKeepsTheNotificationOfAnActiveSession() {
        val active = running(2, 1)
        assertEquals(active, foregroundStateFor(active, Mode.Hotspot))
        val starting = SharingState.Starting(Mode.Wifi)
        assertEquals(starting, foregroundStateFor(starting, Mode.Hotspot))
    }

    @Test
    fun aStartCommandOnAnIdleOrFailedServiceShowsStarting() {
        assertEquals(SharingState.Starting(Mode.Hotspot), foregroundStateFor(SharingState.Idle, Mode.Hotspot))
        assertEquals(SharingState.Starting(Mode.Wifi), foregroundStateFor(SharingState.Error("old", null), Mode.Wifi))
    }
}
