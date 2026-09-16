package com.cashup.app.scan

import com.cashup.devicesdk.Scanner

/**
 * Mencoba scanner hardware vendor dulu, jatuh ke kamera kalau perlu.
 *
 * Kamera dibuat lewat lambda, bukan diterima jadi, supaya CameraX tidak
 * di-inisialisasi sama sekali di terminal yang punya scanner hardware — di
 * device 1 GB, preview yang tidak pernah dipakai tetap memakan memori.
 *
 * Vendor mengembalikan `null` baik saat scanner tidak ada maupun saat waktunya
 * habis. Keduanya diperlakukan sama: coba kamera. Teknisi yang menunggu lebih
 * peduli pada QR-nya terbaca daripada pada alat mana yang membacanya.
 */
class QrScanSource(
    private val vendor: Scanner?,
    private val camera: () -> Scanner,
) {
    suspend fun scan(timeoutMillis: Long): String? =
        vendor?.scanQr(timeoutMillis) ?: camera().scanQr(timeoutMillis)
}
