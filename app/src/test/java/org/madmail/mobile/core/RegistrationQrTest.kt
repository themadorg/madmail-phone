package org.madmail.mobile.core

import com.google.zxing.*
import com.google.zxing.common.HybridBinarizer
import org.junit.Assert.assertEquals
import org.junit.Test

class RegistrationQrTest {
    @Test fun invitationSurvivesQrRoundTrip() = roundTrip(InviteLinks.invite("https://mail.example.org", "invite+token"))
    @Test fun accountSurvivesQrRoundTrip() = roundTrip(InviteLinks.login("dclogin:user@madmail.local/?p=long%2Bpassword%26value&v=1&ih=localhost&ip=11993&is=ssl&sh=localhost&sp=11465&ss=ssl&ic=3", "https://192.168.1.2:18443"))
    private fun roundTrip(payload: String) {
        val matrix = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, 768, 768)
        val pixels = IntArray(768 * 768) { i -> if (matrix[i % 768, i / 768]) 0xff000000.toInt() else 0xffffffff.toInt() }
        val source = RGBLuminanceSource(768, 768, pixels)
        val decoded = MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)))
        assertEquals(payload, decoded.text)
    }
}
