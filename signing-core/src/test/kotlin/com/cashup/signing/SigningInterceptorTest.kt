package com.cashup.signing

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

        assertThrows(DeviceNotProvisionedException::class.java) {
            client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).execute()
        }
    }

    @Test
    fun `surfaces DeviceNotProvisionedException via onFailure on the async enqueue path instead of crashing`() {
        val unprovisioned = SigningKeyProvider { null }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(unprovisioned))
            .build()

        val latch = CountDownLatch(1)
        val failure = AtomicReference<Throwable>()

        client.newCall(Request.Builder().url(server.url("/cdcp/sale")).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                failure.set(e)
                latch.countDown()
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                latch.countDown()
            }
        })

        assertTrue(latch.await(5, TimeUnit.SECONDS), "onFailure/onResponse callback was never invoked")
        assertTrue(failure.get() is DeviceNotProvisionedException)
    }

    @Test
    fun `signs and preserves the request body on POST`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val keyProvider = SigningKeyProvider { keyPair }
        val client = OkHttpClient.Builder()
            .addInterceptor(SigningInterceptor(keyProvider))
            .build()

        val jsonBody = """{"amount":"10000"}"""
        val body = jsonBody.toRequestBody("application/json".toMediaType())
        client.newCall(Request.Builder().url(server.url("/cdcp/sale")).post(body).build()).execute()

        val recorded = server.takeRequest()
        assertEquals(jsonBody, recorded.body.readUtf8())

        val signature = recorded.getHeader("X-Signature")!!
        val timestamp = recorded.getHeader("X-Timestamp")!!.toLong()
        val nonce = recorded.getHeader("X-Nonce")!!
        val signer = RequestSigner()
        val canonical = signer.canonicalize("POST", "/cdcp/sale", timestamp, nonce, jsonBody.toByteArray())
        assertTrue(signer.verify(canonical, signature, keyPair.public))
    }
}
