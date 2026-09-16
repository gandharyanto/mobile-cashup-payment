package com.cashup.common.network

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET

data class Payload(val orderId: String)

interface EnvelopeTestApi {
    @GET("/probe")
    suspend fun probe(): Response<ApiEnvelope<Payload>>
}

class SafeEnvelopeCallTest {

    private lateinit var server: MockWebServer
    private lateinit var api: EnvelopeTestApi

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(OkHttpClient())
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(EnvelopeTestApi::class.java)
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `unwraps data on success`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1"},"meta":{"correlationId":"c-1"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertInstanceOf(ApiResult.Success::class.java, result)
        assertEquals("o-1", (result as ApiResult.Success).data.orderId)
    }

    @Test
    fun `reads the backend error code out of a non-2xx envelope`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(410)
                .setBody("""{"error":{"code":"PROVISIONING_TOKEN_INVALID","message":"Kode QR kedaluwarsa"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertInstanceOf(ApiResult.Failure::class.java, result)
        val error = (result as ApiResult.Failure).error
        assertEquals("PROVISIONING_TOKEN_INVALID", error.code)
        assertEquals("Kode QR kedaluwarsa", error.message)
        assertEquals(410, error.httpStatus)
    }

    @Test
    fun `reads an error envelope that arrives with a 2xx status`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"error":{"code":"TERMINAL_INACTIVE","message":"Device tidak aktif"}}""")
        )

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("TERMINAL_INACTIVE", (result as ApiResult.Failure).error.code)
    }

    @Test
    fun `falls back to a readable code when the body is not JSON`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>bad gateway</html>"))

        val result = safeEnvelopeCall { api.probe() }

        val error = (result as ApiResult.Failure).error
        assertEquals("RESPONSE_UNREADABLE", error.code)
        assertEquals(502, error.httpStatus)
    }

    @Test
    fun `flags a 2xx response that carries neither data nor error`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"meta":{"correlationId":"c-9"}}"""))

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("RESPONSE_UNEXPECTED", (result as ApiResult.Failure).error.code)
    }

    @Test
    fun `maps a transport failure to NETWORK`() = runBlocking {
        server.shutdown()

        val result = safeEnvelopeCall { api.probe() }

        assertEquals("NETWORK", (result as ApiResult.Failure).error.code)
    }
}