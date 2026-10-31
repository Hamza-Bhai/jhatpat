package io.github.hamzabhai.jhatpat.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import io.github.hamzabhai.jhatpat.logic.WifiQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {
    private fun decode(text: String): String {
        val px = QrCode.pixels(text)
        val source = RGBLuminanceSource(px.width, px.height, px.argb)
        return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text
    }

    @Test
    fun joinLinkRoundTrips() {
        val link = "http://192.168.43.1:8787/j/TxNUB1UnxiUkM8WMnnpw3IOQa_90fig8Oo_TWPFALik"
        assertEquals(link, decode(link))
    }

    @Test
    fun wifiQrWithSpecialCharactersRoundTrips() {
        val text = WifiQr.build("Café;Net", "pa;ss,w:o\\rd\"")
        assertEquals(text, decode(text))
    }

    @Test
    fun unicodeRoundTrips() {
        assertEquals("🎉 شادی", decode("🎉 شادی"))
    }

    @Test
    fun hasAQuietZoneAndIsSquare() {
        val px = QrCode.pixels("hello")
        assertEquals(px.width, px.height)
        assertEquals(px.width * px.height, px.argb.size)
        // the first row is all quiet zone (white)
        assertTrue((0 until px.width).all { px.argb[it] == 0xFFFFFFFF.toInt() })
    }
}
