package com.cashup.signing

import org.junit.jupiter.api.Assertions.assertEquals
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
            requestTarget = "/cdcp/sale",
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

    @Test
    fun `same path with different query strings produces different canonical bytes and signatures`() {
        val keyPair = generateKeyPair()
        val canonicalOne = signer.canonicalize("GET", "/cdcp/status?txnId=1", 1_726_300_000_000, "xyz", ByteArray(0))
        val canonicalTwo = signer.canonicalize("GET", "/cdcp/status?txnId=999", 1_726_300_000_000, "xyz", ByteArray(0))

        assertFalse(canonicalOne.contentEquals(canonicalTwo))

        val signatureOne = signer.sign(canonicalOne, keyPair.private)
        assertFalse(signer.verify(canonicalTwo, signatureOne, keyPair.public))
    }

    @Test
    fun `canonical form is byte-stable for a known input`() {
        val canonical = signer.canonicalize(
            method = "post",
            requestTarget = "/cdcp/sale",
            timestampMillis = 1_726_300_000_000,
            nonce = "abc123",
            body = """{"amount":"10000"}""".toByteArray(),
        )
        val expectedBodyHash = java.util.Base64.getEncoder().encodeToString(
            java.security.MessageDigest.getInstance("SHA-256").digest("""{"amount":"10000"}""".toByteArray())
        )
        val expected = "POST\n/cdcp/sale\n1726300000000\nabc123\n$expectedBodyHash"
        assertEquals(expected, String(canonical, Charsets.UTF_8))
    }
}
