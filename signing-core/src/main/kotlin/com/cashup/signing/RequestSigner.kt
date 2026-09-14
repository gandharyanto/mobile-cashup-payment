package com.cashup.signing

import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Canonicalizes an outgoing request and produces an ECDSA signature over it.
 * The canonical form is deliberately simple and stable: method, request
 * target (encoded path + query string, if any), timestamp, nonce and a hash
 * of the body, newline-separated. Byte-for-byte stability matters here —
 * reordering fields later invalidates every signature already accepted by
 * the backend.
 *
 * `requestTarget` MUST include the query string when the request has one
 * (e.g. `/cdcp/status?txnId=1`) — omitting it lets two requests that only
 * differ by query string produce identical canonical bytes, which defeats
 * the signature as a protection for the query string. This does NOT bind
 * the signature to a host; that is a deliberate scope limit, not an
 * oversight.
 */
class RequestSigner {

    fun canonicalize(method: String, requestTarget: String, timestampMillis: Long, nonce: String, body: ByteArray): ByteArray {
        val bodyHash = sha256(body)
        return listOf(
            method.uppercase(),
            requestTarget,
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
