package io.github.hamzabhai.jhatpat.net

import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotLogicTest {
    @Test
    fun startedUsesThePickedAddress() {
        val r = HotspotLogic.result("DIRECT-ab-JhatPat", "pw123456", listOf("wlan0" to "192.168.1.5", "ap0" to "192.168.43.1"))
        assertEquals(HotspotResult.Started("DIRECT-ab-JhatPat", "pw123456", "192.168.43.1"), r)
    }

    @Test
    fun failsWithUnknownWhenNoUsableAddress() {
        assertEquals(HotspotResult.Failed(Reason.Unknown), HotspotLogic.result("s", "p", listOf("lo" to "127.0.0.1")))
        assertEquals(HotspotResult.Failed(Reason.Unknown), HotspotLogic.result("s", "p", emptyList()))
    }

    @Test
    fun requiredPermissionsDependOnTheAndroidVersion() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), HotspotLogic.requiredPermissions(26))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), HotspotLogic.requiredPermissions(32))
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), HotspotLogic.requiredPermissions(33))
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), HotspotLogic.requiredPermissions(35))
    }

    @Test
    fun locationMustBeOnBeforeAndroid13() {
        assertEquals(true, HotspotLogic.needsLocationServices(30))
        assertEquals(true, HotspotLogic.needsLocationServices(32))
        assertEquals(false, HotspotLogic.needsLocationServices(33))
    }

    @Test
    fun failureCodesMapToReasons() {
        assertEquals(Reason.Busy, HotspotLogic.reasonFor(1)) // ERROR_NO_CHANNEL
        assertEquals(Reason.Unknown, HotspotLogic.reasonFor(2)) // ERROR_GENERIC
        assertEquals(Reason.Busy, HotspotLogic.reasonFor(3)) // ERROR_INCOMPATIBLE_MODE
        assertEquals(Reason.Unsupported, HotspotLogic.reasonFor(4)) // ERROR_TETHERING_DISALLOWED
        assertEquals(Reason.Unknown, HotspotLogic.reasonFor(99))
    }

    @Test
    fun stripsQuotesFromLegacyWifiConfigurationValues() {
        assertEquals("AndroidShare_1234", HotspotLogic.unquote("\"AndroidShare_1234\""))
        assertEquals("plain", HotspotLogic.unquote("plain"))
        assertEquals("", HotspotLogic.unquote(null))
        assertEquals("\"", HotspotLogic.unquote("\""))
    }

    @Test
    fun wifiClientAddressPrefersWlan0() {
        assertEquals("192.168.1.5", WifiClientAddress.pick(listOf("rmnet0" to "10.20.30.40", "wlan0" to "192.168.1.5")))
        assertEquals("192.168.1.5", WifiClientAddress.pick(listOf("ap0" to "192.168.43.1", "wlan0" to "192.168.1.5")))
    }

    @Test
    fun wifiClientAddressFallsBackToEthernetAndIgnoresMobileData() {
        assertEquals("192.168.0.9", WifiClientAddress.pick(listOf("eth0" to "192.168.0.9")))
        assertEquals(null, WifiClientAddress.pick(listOf("rmnet_data0" to "10.1.2.3", "lo" to "127.0.0.1")))
        assertEquals(null, WifiClientAddress.pick(emptyList()))
    }
}
