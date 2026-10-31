package io.github.hamzabhai.jhatpat.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Draws QR codes with ZXing. The pixel step is plain JVM code so it can be unit tested (by decoding it). */
object QrCode {
    class Pixels(val width: Int, val height: Int, val argb: IntArray)

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    /** [modulePx] pixels per QR square, with a white border of [quietModules] squares all round. */
    fun pixels(text: String, modulePx: Int = 6, quietModules: Int = 4): Pixels {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = (matrix.width + 2 * quietModules) * modulePx
        val argb = IntArray(size * size) { WHITE }
        for (my in 0 until matrix.height) {
            for (mx in 0 until matrix.width) {
                if (!matrix[mx, my]) continue
                val x0 = (mx + quietModules) * modulePx
                val y0 = (my + quietModules) * modulePx
                for (dy in 0 until modulePx) {
                    val row = (y0 + dy) * size + x0
                    for (dx in 0 until modulePx) argb[row + dx] = BLACK
                }
            }
        }
        return Pixels(size, size, argb)
    }

    fun bitmap(text: String): Bitmap {
        val px = pixels(text)
        return Bitmap.createBitmap(px.argb, px.width, px.height, Bitmap.Config.ARGB_8888)
    }
}

@Composable
fun QrImage(text: String, sizeDp: Int, modifier: Modifier = Modifier) {
    if (text.isEmpty()) {
        Box(modifier.size(sizeDp.dp))
        return
    }
    val bitmap = remember(text) { QrCode.bitmap(text).asImageBitmap() }
    Image(
        bitmap = bitmap,
        contentDescription = "QR code",
        modifier = modifier.size(sizeDp.dp),
        filterQuality = FilterQuality.None,
    )
}
