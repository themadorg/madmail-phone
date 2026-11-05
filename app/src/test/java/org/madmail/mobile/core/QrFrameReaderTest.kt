package org.madmail.mobile.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.*
import org.junit.Test

class QrFrameReaderTest {
    private val text = "eyJ1IjoiaHR0cHM6Ly8yMDMuMC4xMTMuNy9hcGkvYWRtaW4iLCJ0IjoiMDEyMzQ1Njc4OWFiY2RlZiJ9"

    /** A camera-like frame: padded rows, the code drawn [scale]× with a 4-module quiet zone. */
    private fun frame(inverted: Boolean, rowStride: Int = 0): Triple<ByteArray, Int, Pair<Int, Int>> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 4))
        val scale = 4
        val width = matrix.width * scale; val height = matrix.height * scale
        val stride = maxOf(rowStride, width)
        val dark: Byte = 20; val light: Byte = -36 // 220 unsigned
        val bytes = ByteArray(stride * height) { 0x7F }
        for (y in 0 until height) for (x in 0 until width) {
            val on = matrix[x / scale, y / scale] != inverted
            bytes[y * stride + x] = if (on) dark else light
        }
        return Triple(bytes, stride, width to height)
    }

    @Test fun readsDarkOnLight() {
        val (bytes, stride, size) = frame(inverted = false)
        assertEquals(text, QrFrameReader().read(bytes, stride, size.first, size.second))
    }

    @Test fun readsTerminalStyleLightOnDarkWithRowPadding() {
        val (bytes, stride, size) = frame(inverted = true, rowStride = 512)
        assertEquals(text, QrFrameReader().read(bytes, stride, size.first, size.second))
    }

    @Test fun blankFrameFindsNothing() {
        assertNull(QrFrameReader().read(ByteArray(64 * 64) { 0x40 }, 64, 64, 64))
    }
}
