package com.cashup.signing

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class SigningInterceptorTest {

    private lateinit var server: MockWebServer
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private inner class TestSigner(private val id: String?) : DeviceSigner {
        override fun deviceId(): String? = id
        override fun sign(canonicalBytes: ByteArray): ByteArray =
            Signature.getInstance("Ed25519").run {
                initSign(keyPair.private)
                update(canonicalBytes)
                sign()
            }
    }

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun clientFor(signer: DeviceSigner) =
        OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(signer, nonceFactory = { "nonce-1" }))
            .build()

    private fun post(url: String, body: String) = Request.Builder()
        .url(url)
        .header("X-Timestamp", "2026-09-16T10:30+07:00")
        .post(body.toRequestBody("application/json".toMediaType()))
        .build()

    private fun verifies(path: String, body: String, signatureHeader: String?): Boolean {
        val canonical = Ed25519RequestSigner().canonicalize(
            method = "POST",
            path = path,
            deviceId = "dev-1",
            timestamp = "2026-09-16T10:30+07:00",
            nonce = "nonce-1",
            body = body.toByteArray(),
        )
        return Signature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(canonical)
            verify(Base64.getUrlDecoder().decode(signatureHeader))
        }
    }

    @Test
    fun `adds device id, nonce and a signature the public key verifies`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val body = """{"orderId":"abc"}"""

        clientFor(TestSigner("dev-1"))
            .newCall(post(server.url("/v1/orders/abc/package").toString(), body))
            .execute().close()

        val recorded = server.takeRequest()
        assertEquals("dev-1", recorded.getHeader("X-Device-Id"))
        assertEquals("nonce-1", recorded.getHeader("X-Nonce"))
        assertNotNull(recorded.getHeader("X-Signature"))
        assertTrue(verifies("/v1/orders/abc/package", body, recorded.getHeader("X-Signature")))
    }

    @Test
    fun `throws DeviceNotProvisionedException when there is no device id`() {
        assertThrows(DeviceNotProvisionedException::class.java) {
            clientFor(TestSigner(null))
                .newCall(post(server.url("/v1/x").toString(), "{}"))
                .execute()
        }
    }

    @Test
    fun `throws MissingTimestampException when RequestHeadersInterceptor did not run`() {
        val request = Request.Builder()
            .url(server.url("/v1/x"))
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()

        assertThrows(MissingTimestampException::class.java) {
            clientFor(TestSigner("dev-1")).newCall(request).execute()
        }
    }

    @Test
    fun `the signed path excludes the query string`() {
        server.enqueue(MockResponse().setResponseCode(200))

        clientFor(TestSigner("dev-1"))
            .newCall(post(server.url("/v1/payments?status=PENDING").toString(), "{}"))
            .execute().close()

        val recorded = server.takeRequest()
        assertTrue(verifies("/v1/payments", "{}", recorded.getHeader("X-Signature")))
    }
}