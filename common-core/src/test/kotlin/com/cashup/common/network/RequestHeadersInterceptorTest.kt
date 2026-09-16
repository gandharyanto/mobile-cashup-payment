package com.cashup.common.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Date
import java.util.TimeZone

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
    fun `writes the timestamp and correlation id it is given`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = clientWith(
            RequestHeadersInterceptor(
                clock = { "2026-09-16T10:30:00+07:00" },
                correlationIdFactory = { "corr-1" },
            )
        )

        client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()

        val recorded = server.takeRequest()
        Assertions.assertEquals("2026-09-16T10:30:00+07:00", recorded.getHeader("X-Timestamp"))
        Assertions.assertEquals("corr-1", recorded.getHeader("X-Correlation-Id"))
    }

    /**
     * Zona waktu diberikan eksplisit, bukan diambil dari host: mesin CI tidak
     * semuanya berjalan di Asia/Jakarta, dan tes yang bergantung pada itu lulus
     * di laptop lalu gagal di CI.
     */
    @Test
    fun `the default timestamp is ISO-8601 with a colon in the offset`() {
        val instant = Date(1789529400000L) // 2026-09-16T03:30:00Z

        Assertions.assertEquals(
            "2026-09-16T10:30:00+07:00",
            RequestHeadersInterceptor.isoTimestamp(instant, TimeZone.getTimeZone("Asia/Jakarta")),
        )
        Assertions.assertEquals(
            "2026-09-16T03:30:00+00:00",
            RequestHeadersInterceptor.isoTimestamp(instant, TimeZone.getTimeZone("UTC")),
        )
    }

    /**
     * Regresi API level: interceptor ini berjalan di device `minSdk` 23, jadi
     * nilai default-nya tidak boleh menyentuh `java.time.*` (API 26). Bentuknya
     * dicek lewat pola, bukan nilai, supaya tidak bergantung pada waktu atau
     * zona mesin yang menjalankannya.
     */
    @Test
    fun `the default clock produces an ISO-8601 offset string`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val client = clientWith(RequestHeadersInterceptor())

        client.newCall(Request.Builder().url(server.url("/v1/ping")).build()).execute().close()

        val timestamp = server.takeRequest().getHeader("X-Timestamp")!!
        Assertions.assertTrue(
            Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[+-][0-9]{2}:[0-9]{2}")
                .matches(timestamp),
            timestamp,
        )
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