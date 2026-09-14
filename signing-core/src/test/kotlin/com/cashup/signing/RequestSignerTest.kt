package com.cashup.signing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class RequestSignerTest {

    private val signer = RequestSigner()

    private fun generateKeyPair() =
        KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

    @Test
    fun `a signature verifies against the same canonical bytes and key pair`() {
        val keyPair = generateKeyPair()
        val canonical = signer.canonicalize(
            method = "POST",
            path = "/cdcp/sale",
            timestampMillis = 1_726_300_000_000,
            nonce = "abc123",
            body = """{"amount":"10000"}""".toByteArray(),
        )

        val signature = signer.sign(canonical, keyPair.private)

        assertTrue(signer.verify(canonical, signature, keyPair.public))
    }

    @Test
    fun `verification fails when the body changes after signing`() {
        val keyPair = generateKeyPair()
        val signedCanonical = signer.canonicalize("POST", "/cdcp/sale", 1_726_300_000_000, "abc123", """{"amount":"10000"}""".toByteArray())
        val signature = signer.sign(signedCanonical, keyPair.private)

        val tamperedCanonical = signer.canonicalize("POST", "/cdcp/sale", 1_726_300_000_000, "abc123", """{"amount":"99999999"}""".toByteArray())

        assertFalse(signer.verify(tamperedCanonical, signature, keyPair.public))
    }

    @Test
    fun `verification fails against a different key pair`() {
        val keyPair = generateKeyPair()
        val otherKeyPair = generateKeyPair()
        val canonical = signer.canonicalize("GET", "/cdcp/status/1", 1_726_300_000_000, "xyz", ByteArray(0))
        val signature = signer.sign(canonical, keyPair.private)

        assertFalse(signer.verify(canonical, signature, otherKeyPair.public))
    }
}
