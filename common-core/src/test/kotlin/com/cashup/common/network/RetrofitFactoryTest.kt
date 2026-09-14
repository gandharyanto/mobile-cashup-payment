package com.cashup.common.network

import okhttp3.Interceptor
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Call
import retrofit2.http.GET

private interface PingService {
    @GET("/ping")
    fun ping(): Call<ResponseBody>
}

class RetrofitFactoryTest {

    private lateinit var server: MockWebServer

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
    fun `applies every configured interceptor to outgoing requests`() {
        server.enqueue(MockResponse().setResponseCode(200))

        val marker = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("X-Marker", "present")
                .build()
            chain.proceed(request)
        }

        val retrofit = RetrofitFactory.create(
            baseUrl = server.url("/").toString(),
            interceptors = listOf(marker),
        )
        retrofit.create(PingService::class.java).ping().execute()

        val recorded = server.takeRequest()
        assertEquals("present", recorded.getHeader("X-Marker"))
    }
}
