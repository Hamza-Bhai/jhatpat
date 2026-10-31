package io.github.hamzabhai.jhatpat.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiQrTest {
    @Test
    fun plainCredentials() {
        assertEquals("WIFI:T:WPA;S:Cafe;P:pass1234;;", WifiQr.build("Cafe", "pass1234"))
    }

    @Test
    fun escapesSpecialCharactersInPassword() {
        assertEquals("""WIFI:T:WPA;S:Cafe;P:a\;b\,c\:d\\e\"f;;""", WifiQr.build("Cafe", """a;b,c:d\e"f"""))
    }

    @Test
    fun escapesSpecialCharactersInSsid() {
        assertEquals("""WIFI:T:WPA;S:My\;Net\:5G;P:x;;""", WifiQr.build("My;Net:5G", "x"))
    }

    @Test
    fun keepsUnicodeAsIs() {
        assertEquals("WIFI:T:WPA;S:Café 🎉;P:pässword;;", WifiQr.build("Café 🎉", "pässword"))
    }
}
