package com.cashup.provisioning.crypto

/**
 * Membuka bungkusan RSA dari paket key.
 *
 * Interface, bukan kelas langsung, karena implementasi nyatanya memakai private
 * key non-extractable di Android Keystore yang tidak bisa dijalankan di unit
 * test JVM. Semua penguraian dan validasi paket diuji terhadap implementasi
 * palsu; lihat Task 9 untuk yang nyata.
 */
fun interface RsaUnwrapper {
    fun unwrap(wrapped: ByteArray): ByteArray
}