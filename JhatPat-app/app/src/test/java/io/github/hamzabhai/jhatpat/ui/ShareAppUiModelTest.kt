package io.github.hamzabhai.jhatpat.ui

import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.server.StartupInfo
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareAppUiModelTest {
    private val info = StartupInfo(8787, "tok", "4827", "sec", "192.168.1.5", "http://localhost:8787/host?t=tok")
    private fun wifi() = SharingState.Running(Mode.Wifi, info, null, 0, 0)

    @Test
    fun theLinkPointsAtTheHostsApk() {
        val ui = ShareAppUiModel.of(wifi(), remainingSeconds = 600)
        assertEquals("http://192.168.1.5:8787/app.apk", ui.url)
        assertEquals("192.168.1.5:8787/app.apk", ui.address)
    }

    @Test
    fun theLinkUsesTheHotspotAddressInHotspotMode() {
        val hotspot = SharingState.Running(
            Mode.Hotspot, info.copy(ip = "192.168.29.99"), HotspotResult.Started("AndroidShare_1", "pw", "192.168.29.99"), 0, 0,
        )
        assertEquals("http://192.168.29.99:8787/app.apk", ShareAppUiModel.of(hotspot, 600).url)
    }

    @Test
    fun noAddressMeansNoLink() {
        val ui = ShareAppUiModel.of(SharingState.Running(Mode.Wifi, info.copy(ip = ""), null, 0, 0), 600)
        assertEquals("", ui.url)
        assertEquals("", ui.address)
    }

    @Test
    fun countdownShowsMinutesAndSeconds() {
        assertEquals("10:00", ShareAppUiModel.countdown(600))
        assertEquals("09:59", ShareAppUiModel.countdown(599))
        assertEquals("00:01", ShareAppUiModel.countdown(1))
        assertEquals("01:05", ShareAppUiModel.countdown(65))
    }

    @Test
    fun expiredAtZeroAndNeverNegative() {
        val running = ShareAppUiModel.of(wifi(), 1)
        assertFalse(running.expired)
        val done = ShareAppUiModel.of(wifi(), 0)
        assertTrue(done.expired)
        assertEquals("00:00", done.countdown)
        assertEquals("00:00", ShareAppUiModel.countdown(-5))
        assertTrue(ShareAppUiModel.of(wifi(), -5).expired)
    }

    @Test
    fun sharingLastsTenMinutes() {
        assertEquals(10, ShareAppUiModel.MINUTES)
    }
}
