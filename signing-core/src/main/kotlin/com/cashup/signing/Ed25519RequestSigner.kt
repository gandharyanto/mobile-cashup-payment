package com.cashup.signing

import java.security.MessageDigest

/**
 * Menyusun bentuk kanonik sebuah request untuk ditandatangani Ed25519.
 *
 * Susunannya, dipisah newline, mengikuti kontrak backend persis:
 *
 * ```
 * METHOD
 * path                 encodedPath saja, TANPA query string
 * deviceId
 * timestamp            nilai header X-Timestamp, ISO-8601 offset
 * nonce
 * sha256hex(body)      hex huruf kecil
 * ```
 *
 * Stabilitas byte-for-byte itu intinya: menukar urutan field, mengganti hex
 * jadi Base64, atau menambah field baru membatalkan setiap tanda tangan yang
 * sudah pernah diterima backend.
 *
 * [path] diteruskan apa adanya. Memisahkan query string adalah tanggung jawab
 * pemanggil ([SigningInterceptor] memakai `request.url.encodedPath`), supaya
 * kesalahan di sana muncul sebagai tanda tangan yang tidak cocok, bukan
 * diperbaiki diam-diam di sini.
 */
class Ed25519RequestSigner {

    fun canonicalize(
        method: String,
        path: String,
        deviceId: String,
        timestamp: String,
        nonce: String,
        body: ByteArray,
    ): ByteArray = listOf(
        method.uppercase(),
        path,
        deviceId,
        timestamp,
        nonce,
        sha256Hex(body),
    ).joinToString("\n").toByteArray(Charsets.UTF_8)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /**
         * Base64 URL-safe tanpa padding, sesuai yang diharapkan backend di `X-Signature`.
         *
         * Ini bukan `java.util.Base64` (yang baru ada di API 26) karena `signing-core`
         * adalah modul `kotlin("jvm")` murni, sengaja dijaga bebas dari Android SDK per
         * Global Constraints rencana ini, dan class ini dieksekusi di runtime `:app` pada
         * setiap request yang ditandatangani — termasuk di device API 23-25, di bawah
         * `minSdk`-nya sendiri tapi tetap target perangkat aplikasi ini. Encoder manual di
         * bawah ini tidak bergantung pada API level Android sama sekali.
         */
        fun encodeSignature(raw: ByteArray): String {
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            val sb = StringBuilder((raw.size + 2) / 3 * 4)
            var i = 0
            while (i < raw.size) {
                val b0 = raw[i].toInt() and 0xFF
                val b1 = if (i + 1 < raw.size) raw[i + 1].toInt() and 0xFF else 0
                val b2 = if (i + 2 < raw.size) raw[i + 2].toInt() and 0xFF else 0

                sb.append(alphabet[b0 ushr 2])
                sb.append(alphabet[(b0 shl 4 or (b1 ushr 4)) and 0x3F])
                if (i + 1 < raw.size) {
                    sb.append(alphabet[(b1 shl 2 or (b2 ushr 6)) and 0x3F])
                }
                if (i + 2 < raw.size) {
                    sb.append(alphabet[b2 and 0x3F])
                }
                i += 3
            }
            return sb.toString()
        }
    }
}