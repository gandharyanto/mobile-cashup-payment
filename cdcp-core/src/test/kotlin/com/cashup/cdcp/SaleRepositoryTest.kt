package com.cashup.cdcp

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.signing.DeviceSigner
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SaleRepositoryTest {
    private class Keys : DukptKeyProvider {
        var count = 0
        override fun nextCounter() = ++count
        override fun load(purpose: String) = TerminalKeyMaterial(purpose,
            ByteArray(16) { (it + 1).toByte() }, ByteArray(10) { (it + 1).toByte() })
    }
    private val signer = object : DeviceSigner {
        override fun deviceId() = "backend-device-id"
        override fun sign(canonicalBytes: ByteArray) = ByteArray(64) { 7 }
    }

    @Test fun `sale uses signed endpoint and stable idempotent payload on retry`() = runBlocking {
        val server = MockWebServer().apply { start() }
        try {
            val keys = Keys()
            val repository = SaleRepository.create(server.url("/").toString(), signer, keys)
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":{"code":"HOST_BUSY","message":"Retry"}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"APPROVED"}}"""))
            val card = CardCatalog.cards.first()
            val first = repository.sale("backend-device-id", 2, card, BigDecimal("10000"), idempotencyKey = "action-1")
            val second = repository.sale("backend-device-id", 2, card, BigDecimal("10000"), idempotencyKey = "action-1")
            assertEquals("HOST_BUSY", (first as ApiResult.Failure).error.code)
            assertEquals("APPROVED", (second as ApiResult.Success).data.status)
            assertEquals(1, keys.count)
            val request1 = server.takeRequest()
            val request2 = server.takeRequest()
            assertEquals("POST", request1.method)
            assertEquals("/v1/cdcp/sales", request1.path)
            assertEquals("action-1", request1.getHeader("Idempotency-Key"))
            assertEquals("backend-device-id", request1.getHeader("X-Device-Id"))
            assertNotNull(request1.getHeader("X-Signature"))
            assertEquals(request1.body.readUtf8(), request2.body.readUtf8())
        } finally { server.shutdown() }
    }
}
