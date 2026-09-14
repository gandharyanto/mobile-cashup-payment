package com.cashup.signing

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.util.UUID

/**
 * Signs every request that passes through it. Attach this only to clients
 * that talk to the Front-facing API after provisioning — provisioning's own
 * bootstrap call uses the temporary admin JWT instead (Provisioning plan),
 * never this interceptor.
 *
 * Fails loudly (throws) rather than sending an unsigned request when no key
 * is available yet: an unsigned request reaching this interceptor is a
 * wiring bug, not a recoverable state.
 */
class SigningInterceptor(
    private val keyProvider: SigningKeyProvider,
    private val signer: RequestSigner = RequestSigner(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val keyPair = keyProvider.currentKeyPair()
            ?: error("SigningInterceptor invoked before the device is provisioned")

        val request = chain.request()
        val bodyBytes = request.body?.let { body ->
            Buffer().also { body.writeTo(it) }.readByteArray()
        } ?: ByteArray(0)

        val timestamp = clock()
        val nonce = nonceFactory()
        val canonical = signer.canonicalize(
            method = request.method,
            path = request.url.encodedPath,
            timestampMillis = timestamp,
            nonce = nonce,
            body = bodyBytes,
        )
        val signature = signer.sign(canonical, keyPair.private)

        val signedRequest = request.newBuilder()
            .addHeader("X-Signature", signature)
            .addHeader("X-Timestamp", timestamp.toString())
            .addHeader("X-Nonce", nonce)
            .build()

        return chain.proceed(signedRequest)
    }
}
