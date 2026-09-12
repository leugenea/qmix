package com.qmix.tv

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GuestInviteTest {
    @Test
    fun qr_decodes_to_absolute_guest_url_without_host_token() {
        val credentials = RoomCredentials(
            code = "AB CD",
            hostToken = "host-secret",
            relativeGuestUrl = "/join/AB%20CD?host_token=host-secret",
        )

        val invite = GuestInvite.create(credentials, "https://guest.example/public/")
        val qr = QrCodeGenerator.generate(invite.guestUrl, 320)
        val decoded = QRCodeReader().decode(
            BinaryBitmap(HybridBinarizer(RGBLuminanceSource(qr.width, qr.height, qr.pixels))),
        ).text

        assertEquals("AB CD", invite.code)
        assertEquals("https://guest.example/join/AB%20CD", invite.guestUrl)
        assertEquals(invite.guestUrl, decoded)
        assertFalse(invite.toString().contains("host-secret"))
        assertFalse(decoded.contains("host_token"))
    }
}
