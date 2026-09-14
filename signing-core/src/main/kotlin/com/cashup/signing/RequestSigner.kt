package com.cashup.signing

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Canonicalizes an outgoing request and produces an ECDSA signature over it.
 * The canonical form is deliberately simple and stable: method, path,
 * timestamp, nonce and a hash of the body, newline-separated. Byte-for-byte
 * stability matters here — reordering fields later invalidates every
 * signature already accepted by the backend.
 */
class RequestSigner {

    fun canonicalize(method: String, path: String, timestampMillis: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = sha256(body)
        return listOf(
            method.uppercase(),
            path,
            timestampMillis.toString(),
            nonce,
            Base64.getEncoder().encodeToString(bodyHash),
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    fun sign(canonicalBytes: ByteArray, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initSign(privateKey)
        signature.update(canonicalBytes)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verify(canonicalBytes: ByteArray, signatureBase64: String, publicKey: PublicKey): Boolean {
        val signature = Signature.getInstance(ALGORITHM)
        signature.initVerify(publicKey)
        signature.update(canonicalBytes)
        return signature.verify(Base64.getDecoder().decode(signatureBase64))
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    companion object {
        const val ALGORITHM = "SHA256withECDSA"
    }
}
