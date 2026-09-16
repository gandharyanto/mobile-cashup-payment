package com.cashup.signing

/**
 * Identitas penandatangan milik device.
 *
 * Mengekspos operasi tanda tangan, bukan `java.security.KeyPair`, karena di
 * Android private key Ed25519 tidak tinggal di objek `KeyPair` yang bisa
 * diedarkan — implementasinya memuat blob PKCS8 dari penyimpanan ter-enkripsi,
 * memakainya, lalu membuangnya. Antarmuka yang mengembalikan `KeyPair` akan
 * memaksa key material itu hidup lebih lama dari yang perlu.
 *
 * [deviceId] mengembalikan `null` sebelum device terprovisioning. Sesuai
 * keputusan di spec §3.3, nilainya adalah nomor seri hardware, bukan UUID
 * terbitan backend.
 */
interface DeviceSigner {
    fun deviceId(): String?

    /** Tanda tangan Ed25519 mentah (64 byte) atas [canonicalBytes]. */
    fun sign(canonicalBytes: ByteArray): ByteArray
}