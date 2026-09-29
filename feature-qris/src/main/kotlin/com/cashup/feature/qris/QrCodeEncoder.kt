package com.cashup.feature.qris

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeEncoder {
    /** Murni JVM (tanpa Android); ubah ke Bitmap di layer UI. Panggil di background thread. */
    fun encode(content: String, size: Int): BitMatrix = QRCodeWriter().encode(
        content, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
}
