package com.qmix.tv

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import okhttp3.HttpUrl.Companion.toHttpUrl

data class GuestInvite(
    val code: String,
    val guestUrl: String,
) {
    companion object {
        fun create(credentials: RoomCredentials, guestOrigin: String): GuestInvite {
            val origin = guestOrigin.toHttpUrl().newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .build()
            require(credentials.relativeGuestUrl.startsWith("/") && !credentials.relativeGuestUrl.startsWith("//"))
            val resolved = requireNotNull(origin.resolve(credentials.relativeGuestUrl))
            require(
                resolved.scheme == origin.scheme &&
                    resolved.host == origin.host &&
                    resolved.port == origin.port,
            )
            val url = resolved.newBuilder().query(null).fragment(null).build()
            return GuestInvite(credentials.code, url.toString())
        }
    }
}

data class QrCode(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
)

object QrCodeGenerator {
    fun generate(contents: String, size: Int): QrCode {
        val matrix = QRCodeWriter().encode(
            contents,
            BarcodeFormat.QR_CODE,
            size,
            size,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return QrCode(size, size, pixels)
    }
}
