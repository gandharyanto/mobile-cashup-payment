package com.cashup.signing

import java.security.MessageDigest
import java.util.Base64

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
        /** Base64 URL-safe tanpa padding, sesuai yang diharapkan backend di `X-Signature`. */
        fun encodeSignature(raw: ByteArray): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}