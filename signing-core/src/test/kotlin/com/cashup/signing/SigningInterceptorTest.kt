package com.cashup.signing

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SigningInterceptorTest {

    private lateinit var server: MockWebServer
    private val keyPair = KeyPairGenerator.getInstance("EC").apply {
        initialize(ECGenParameterSpec("secp256r1"))
    }.generateKeyPair()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `adds a verifiable signature header to every request`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val keyProvider = SigningKeyProvider { keyPair }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(keyProvider))
            .build()

        client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).execute()

        val recorded = server.takeRequest()
        val signature = recorded.getHeader("X-Signature")!!
        val timestamp = recorded.getHeader("X-Timestamp")!!.toLong()
        val nonce = recorded.getHeader("X-Nonce")!!

        val signer = RequestSigner()
        val canonical = signer.canonicalize("GET", "/cdcp/sale", timestamp, nonce, ByteArray(0))
        assertTrue(signer.verify(canonical, signature, keyPair.public))
    }

    @Test
    fun `refuses to send a request when the device is not provisioned`() {
        val unprovisioned = SigningKeyProvider { null }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(unprovisioned))
            .build()

        assertThrows(IllegalStateException::class.java) {
            client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).execute()
        }
    }
}
