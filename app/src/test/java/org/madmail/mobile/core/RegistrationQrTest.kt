package org.madmail.mobile.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationQrTest {
    @Test fun invitationSurvivesQrRoundTrip() {
        val payload = Registration.payload("https://mail.example.org/new_email?t=invite%2Btoken")!!
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 768, 768)
        val pixels = IntArray(768 * 768) { i -> if (matrix[i % 768, i / 768]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val source = RGBLuminanceSource(768, 768, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(payload, decoded.text)
    }
}
