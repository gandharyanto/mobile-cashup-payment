package com.cashup.signing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class Ed25519RequestSignerTest {

    private val signer = Ed25519RequestSigner()

    @Test
    fun `canonical string keeps the agreed field order`() {
        val canonical = signer.canonicalize(
            method = "post",
            path = "/v1/terminal-key-provisioning/orders/abc/package",
            deviceId = "PAX-A920-0012938",
            timestamp = "2026-09-16T10:30+07:00",
            nonce = "nonce-1",
            body = "{}".toByteArray(),
        ).decodeToString()

        val lines = canonical.lines()
        assertEquals(6, lines.size)
        assertEquals("POST", lines[0])
        assertEquals("/v1/terminal-key-provisioning/orders/abc/package", lines[1])
        assertEquals("PAX-A920-0012938", lines[2])
        assertEquals("2026-09-16T10:30+07:00", lines[3])
        assertEquals("nonce-1", lines[4])
        assertEquals(64, lines[5].length, "body hash must be 64 hex chars")
        assertTrue(lines[5].all { it in "0123456789abcdef" }, "body hash must be lowercase hex")
    }

    @Test
    fun `an empty body hashes the empty byte array`() {
        val canonical = signer.canonicalize("GET", "/v1/ping", "dev-1", "t", "n", ByteArray(0))

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical.decodeToString().lines().last(),
        )
    }

    @Test
    fun `the path is passed through verbatim, query included`() {
        val canonical = signer.canonicalize("GET", "/v1/payments?status=PENDING", "d", "t", "n", ByteArray(0))

        // Kontrak backend menandatangani encodedPath saja. Memisahkan query
        // adalah tanggung jawab pemanggil, bukan signer -- dikunci di sini
        // supaya kesalahan pemanggil muncul sebagai tanda tangan yang tidak
        // cocok, bukan diperbaiki diam-diam di lapisan ini.
        assertEquals("/v1/payments?status=PENDING", canonical.decodeToString().lines()[1])
    }

    /**
     * Mengunci encoder Base64 buatan tangan di [Ed25519RequestSigner] terhadap
     * implementasi JDK, untuk setiap panjang dari 0 sampai 199 byte.
     *
     * Encoder itu ditulis manual karena `java.util.Base64` baru ada di API 26
     * sementara class ini berjalan di device API 23 (lihat KDoc-nya). Encoder
     * buatan sendiri di jalur tanda tangan adalah tempat yang mahal untuk
     * salah: outputnya tidak pernah diperiksa di sisi device, hanya ditolak
     * backend, dan penanganan sisa 1/2 byte adalah bagian yang paling mudah
     * meleset. JDK di sini dipakai sebagai orakel test-only -- ia tidak pernah
     * masuk ke kode produksi.
     */
    @Test
    fun `matches the JDK URL-safe unpadded encoder for every length`() {
        val random = java.util.Random(42)
        repeat(200) { length ->
            val bytes = ByteArray(length).also(random::nextBytes)
            assertEquals(
                Base64.getUrlEncoder().withoutPadding().encodeToString(bytes),
                Ed25519RequestSigner.encodeSignature(bytes),
                "length $length",
            )
        }
    }

    @Test
    fun `signature encodes URL-safe without padding and verifies`() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val canonical = signer.canonicalize("POST", "/v1/x", "dev-1", "t", "n", "{}".toByteArray())

        val raw = Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(canonical)
            sign()
        }
        val encoded = Ed25519RequestSigner.encodeSignature(raw)

        assertFalse(encoded.contains('='), "signature must not be padded")
        assertFalse(encoded.contains('+') || encoded.contains('/'), "signature must be URL-safe")

        val verified = Signature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(canonical)
            verify(Base64.getUrlDecoder().decode(encoded))
        }
        assertTrue(verified)
    }
}