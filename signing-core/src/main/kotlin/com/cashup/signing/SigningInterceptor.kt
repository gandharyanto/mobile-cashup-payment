package com.cashup.signing

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID

/**
 * Thrown when [SigningInterceptor] is invoked before the device has a
 * provisioned key pair. This is an [IOException] (not an [IllegalStateException])
 * deliberately: on OkHttp's async `enqueue` path — the normal path for
 * Retrofit `suspend` functions — a non-`IOException` thrown from an
 * interceptor is reported via `onFailure` and then rethrown, crashing the
 * process via the dispatcher thread's uncaught-exception handler. An
 * `IOException` instead surfaces cleanly as a call failure.
 */
class DeviceNotProvisionedException :
    IOException("SigningInterceptor invoked before the device is provisioned")

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
            ?: throw DeviceNotProvisionedException()

        val request = chain.request()
        // Known limitation: one-shot/duplex request bodies cannot be safely
        // read twice (once here for signing, once when OkHttp writes it to
        // the wire), so they are signed as an empty body while the real body
        // is still sent to the wire unchanged. Regular (non-one-shot,
        // non-duplex) bodies — the common case — are signed correctly.
        val bodyBytes = request.body?.takeIf { !it.isOneShot() && !it.isDuplex() }?.let { body ->
            Buffer().also { body.writeTo(it) }.readByteArray()
        } ?: ByteArray(0)

        val timestamp = clock()
        val nonce = nonceFactory()
        val requestTarget = request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        val canonical = signer.canonicalize(
            method = request.method,
            requestTarget = requestTarget,
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
