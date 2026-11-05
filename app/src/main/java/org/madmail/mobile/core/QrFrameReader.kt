package org.madmail.mobile.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer

/**
 * Finds a QR code in one camera frame's luminance (Y) plane. Not thread-safe; use one per analyzer.
 * `madmail admin-token` prints light modules on a dark terminal, so inverted codes are tried too.
 */
internal class QrFrameReader {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.ALSO_INVERTED to true))
    }

    /** [luminance] holds [height] rows of [rowStride] bytes, of which the first [width] are pixels. */
    fun read(luminance: ByteArray, rowStride: Int, width: Int, height: Int): String? = try {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (_: ReaderException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    } finally {
        reader.reset()
    }
}
