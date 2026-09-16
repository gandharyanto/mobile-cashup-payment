package com.cashup.common.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.time.ZoneOffset

class RequestHeadersInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    private fun clientWith(interceptor: RequestHeadersInterceptor) =
        OkHttpClient.Builder().addInterceptor(interceptor).build()

    @Test
    fun `writes an ISO-8601 offset timestamp and a correlation id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val fixed = OffsetDateTime.of(2026, 9, 16, 10, 30, 0, 0, ZoneOffset.ofHours(7))
        val client = clientWith(
            RequestHeadersInterceptor(clock = { fixed }, correlationIdFactory = { "corr-1" })
        )

        client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        Assertions.assertEquals("2026-09-16T10:30+07:00", recorded.getHeader("X-Timestamp"))
        Assertions.assertEquals("corr-1", recorded.getHeader("X-Correlation-Id"))
    }

    @Test
    fun `each attempt gets a fresh correlation id`() {
        server.enqueue(MockResponse().setResponseCode(200))
        server.enqueue(MockResponse().setResponseCode(200))
        var counter = 0
        val client = clientWith(
            RequestHeadersInterceptor(correlationIdFactory = { "corr-" + counter++ })
        )

        repeat(2) {
            client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()
        }

        Assertions.assertEquals("corr-0", server.takeRequest().getHeader("X-Correlation-Id"))
        Assertions.assertEquals("corr-1", server.takeRequest().getHeader("X-Correlation-Id"))
    }
}