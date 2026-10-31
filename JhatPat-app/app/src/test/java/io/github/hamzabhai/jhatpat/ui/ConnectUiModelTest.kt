package io.github.hamzabhai.jhatpat.ui

import io.github.hamzabhai.jhatpat.logic.WifiQr
import io.github.hamzabhai.jhatpat.net.HotspotResult
import io.github.hamzabhai.jhatpat.server.StartupInfo
import io.github.hamzabhai.jhatpat.service.Mode
import io.github.hamzabhai.jhatpat.service.SharingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectUiModelTest {
    private val wifiInfo = StartupInfo(8787, "tok", "4827", "sec-ret", "192.168.1.5", "http://localhost:8787/host?t=tok")
    private val hotspotInfo = wifiInfo.copy(ip = "192.168.43.1")
    private val hotspot = HotspotResult.Started("JhatPat-4F2A", "pa;ss:word", "192.168.43.1")

    private fun wifi() = SharingState.Running(Mode.Wifi, wifiInfo, null, 0, 0)
    private fun hotspotMode() = SharingState.Running(Mode.Hotspot, hotspotInfo, hotspot, 0, 0)

    @Test
    fun wifiModeIsOneQrWithTheJoinLinkAndPin() {
        val ui = ConnectUiModel.of(wifi(), step = 0)
        assertEquals("http://192.168.1.5:8787/j/sec-ret", ui.qrText)
        assertEquals("Scan to join", ui.title)
        assertTrue(ui.showPin)
        assertEquals("4827", ui.pin)
        assertEquals("192.168.1.5:8787", ui.address)
        assertNull(ui.stepLabel)
        assertNull(ui.nextButton)
        assertFalse(ui.hasBack)
    }

    @Test
    fun wifiModeIgnoresTheStep() {
        assertEquals(ConnectUiModel.of(wifi(), 0), ConnectUiModel.of(wifi(), 1))
    }

    @Test
    fun hotspotStepOneIsTheWifiQrAndNothingElse() {
        val ui = ConnectUiModel.of(hotspotMode(), step = 0)
        assertEquals(WifiQr.build("JhatPat-4F2A", "pa;ss:word"), ui.qrText)
        assertEquals("Step 1 of 2 · Join the WiFi", ui.title)
        assertTrue(ui.caption.contains("JhatPat-4F2A"))
        assertFalse(ui.showPin)
        assertNull(ui.address)
        assertEquals("They're connected, next", ui.nextButton)
        assertFalse(ui.hasBack)
    }

    @Test
    fun hotspotStepTwoIsTheJoinLinkWithPinAndBack() {
        val ui = ConnectUiModel.of(hotspotMode(), step = 1)
        assertEquals("http://192.168.43.1:8787/j/sec-ret", ui.qrText)
        assertEquals("Step 2 of 2 · Open JhatPat", ui.title)
        assertTrue(ui.showPin)
        assertEquals("192.168.43.1:8787", ui.address)
        assertNull(ui.nextButton)
        assertTrue(ui.hasBack)
    }

    @Test
    fun hotspotStepOneAlsoShowsTheNameAndPasswordAsTextAndHowToScan() {
        val ui = ConnectUiModel.of(hotspotMode(), step = 0)
        assertEquals("JhatPat-4F2A", ui.wifiName)
        assertEquals("pa;ss:word", ui.wifiPassword)
        assertTrue(ui.caption.contains("Google Lens"))
        assertTrue(ui.caption.contains("Settings"))
    }

    @Test
    fun otherScreensShowNoWifiDetails() {
        assertNull(ConnectUiModel.of(hotspotMode(), step = 1).wifiName)
        assertNull(ConnectUiModel.of(wifi(), step = 0).wifiPassword)
    }

    @Test
    fun stepsAreClamped() {
        assertEquals(ConnectUiModel.of(hotspotMode(), 0), ConnectUiModel.of(hotspotMode(), -3))
        assertEquals(ConnectUiModel.of(hotspotMode(), 1), ConnectUiModel.of(hotspotMode(), 9))
    }

    @Test
    fun hotspotModeWithoutHotspotDetailsFallsBackToTheJoinLink() {
        val broken = SharingState.Running(Mode.Hotspot, hotspotInfo, null, 0, 0)
        val ui = ConnectUiModel.of(broken, 0)
        assertEquals("http://192.168.43.1:8787/j/sec-ret", ui.qrText)
        assertNull(ui.nextButton)
    }

    @Test
    fun noAddressMeansNoQr() {
        val noIp = SharingState.Running(Mode.Wifi, wifiInfo.copy(ip = ""), null, 0, 0)
        val ui = ConnectUiModel.of(noIp, 0)
        assertEquals("", ui.qrText)
        assertTrue(ui.caption.contains("WiFi"))
    }
}
