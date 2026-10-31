package io.github.hamzabhai.jhatpat.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HotspotAddressTest {
    @Test
    fun prefersHotspotInterfaceOverWifiClient() {
        val got = HotspotAddress.pick(listOf("wlan0" to "192.168.1.5", "ap0" to "192.168.43.1"))
        assertEquals("192.168.43.1", got)
    }

    @Test
    fun prefersWlan1AndSwlanOverPlainWlan0() {
        assertEquals("192.168.49.1", HotspotAddress.pick(listOf("wlan0" to "192.168.1.5", "wlan1" to "192.168.49.1")))
        assertEquals("10.0.0.1", HotspotAddress.pick(listOf("wlan0" to "192.168.1.5", "swlan0" to "10.0.0.1")))
    }

    @Test
    fun ignoresLoopback() {
        assertEquals("192.168.1.5", HotspotAddress.pick(listOf("lo" to "127.0.0.1", "wlan0" to "192.168.1.5")))
    }

    @Test
    fun fallsBackToAnyPrivateAddress() {
        assertEquals("172.20.10.1", HotspotAddress.pick(listOf("rndis0" to "172.20.10.1")))
    }

    @Test
    fun emptyIsNull() {
        assertNull(HotspotAddress.pick(emptyList()))
    }

    @Test
    fun onlyPublicOrLoopbackIsNull() {
        assertNull(HotspotAddress.pick(listOf("rmnet0" to "100.64.1.2", "lo" to "127.0.0.1", "x" to "8.8.8.8")))
    }

    @Test
    fun rejectsNonIpv4Text() {
        assertNull(HotspotAddress.pick(listOf("ap0" to "fe80::1", "wlan0" to "not-an-ip", "wlan1" to "192.168.1")))
    }

    @Test
    fun rejectsOutOfRangeOctetsAndTheEdgesOf172() {
        assertNull(HotspotAddress.pick(listOf("ap0" to "192.168.300.1", "wlan0" to "172.15.0.1", "wlan1" to "172.32.0.1")))
        assertEquals("172.31.255.1", HotspotAddress.pick(listOf("ap0" to "172.31.255.1")))
    }
}
