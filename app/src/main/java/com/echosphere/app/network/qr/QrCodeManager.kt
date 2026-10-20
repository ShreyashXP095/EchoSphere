package com.echosphere.app.network.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.echosphere.app.data.model.DiscoveredRoom
import com.echosphere.app.data.model.DiscoveryMethod
import com.echosphere.app.data.model.RoomInfo
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.util.EnumMap

object QrCodeManager {

    /**
     * Generate a crisp QR code Bitmap from RoomInfo for Host sharing
     */
    fun generateQrBitmap(roomInfo: RoomInfo, size: Int = 512): Bitmap? {
        return try {
            val qrContent = roomInfo.toQrString()
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 2)
            }

            val bitMatrix = QRCodeWriter().encode(
                qrContent,
                BarcodeFormat.QR_CODE,
                size,
                size,
                hints
            )

            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)

            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) {
                        Color.BLACK
                    } else {
                        Color.WHITE
                    }
                }
            }

            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, width, 0, 0, width, height)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Parse raw string from camera scanner into DiscoveredRoom
     */
    fun parseScannedRoom(rawPayload: String): DiscoveredRoom? {
        val roomInfo = RoomInfo.fromQrString(rawPayload) ?: return null
        return DiscoveredRoom(
            roomInfo = roomInfo,
            discoveryMethod = DiscoveryMethod.QR_CODE
        )
    }
}
