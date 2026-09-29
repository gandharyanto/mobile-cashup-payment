package com.cashup.qris

import com.cashup.common.network.ApiResult
import com.cashup.signing.DeviceSigner
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrisRepositoryTest {
    private class CapturingSigner : DeviceSigner {
        var canonical: String? = null
        override fun deviceId() = "dev-1"
        override fun sign(canonicalBytes: ByteArray): ByteArray {
            canonical = String(canonicalBytes, Charsets.UTF_8)
            return ByteArray(64) { 7 }
        }
    }

    private fun sha256Hex(text: String) = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private inline fun withServer(block: (MockWebServer, QrisRepository, CapturingSigner) -> Unit) {
        val server = MockWebServer().apply { start() }
        try {
            val signer = CapturingSigner()
            block(server, QrisRepository.create(server.url("/").toString(), signer), signer)
        } finally { server.shutdown() }
    }

    private val qrBody = """{"data":{"transactionId":"tx-1","invoiceNumber":"000001","status":"INITIATED",
        "baseAmount":15000,"currency":"360","qrisHostId":2,"payAppName":"DANA","merchantName":"Toko",
        "qrContent":"00020101","merchantTrxId":"APP-attempt-1"},"meta":{"correlationId":"c"}}""".trimIndent()

    @Test fun `payApps is a signed GET without idempotency key and tolerates null names`() = runBlocking {
        withServer { server, repo, signer ->
            server.enqueue(MockResponse().setBody("""{"data":[
                {"qrisHostId":2,"hostName":"CASHLEZ-AVATAR","payAppName":"DANA","payAppLogoRef":null,
                 "qrSupport":"BOTH","qrExpirySeconds":900,"supportsCancel":false,"supportsRefund":false},
                {"qrisHostId":3,"hostName":"OTHER","payAppName":null,"qrSupport":"STATIC","qrExpirySeconds":600}]}"""))
            val result = repo.payApps()
            val apps = (result as ApiResult.Success).data
            assertEquals(2, apps.size)
            assertEquals("DANA", apps[0].payAppName)
            assertNull(apps[1].payAppName)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/qris/pay-apps", request.path)
            assertEquals("dev-1", request.getHeader("X-Device-Id"))
            assertNotNull(request.getHeader("X-Signature"))
            assertNotNull(request.getHeader("X-Timestamp"))
            assertNotNull(request.getHeader("X-Nonce"))
            assertNull(request.getHeader("Idempotency-Key"))
            // GET meng-hash string kosong (dokumen API).
            val lines = signer.canonical!!.split("\n")
            assertEquals(listOf("GET", "/v1/qris/pay-apps", "dev-1"), lines.take(3))
            assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", lines[5])
        }
    }

    @Test fun `generate posts signed body with attemptId as idempotency key`() = runBlocking {
        withServer { server, repo, signer ->
            server.enqueue(MockResponse().setBody(qrBody))
            val result = repo.generate(2, BigDecimal("15000"), "attempt-1")
            val qr = (result as ApiResult.Success).data
            assertEquals("tx-1", qr.transactionId)
            assertEquals("00020101", qr.qrContent)
            val request = server.takeRequest()
            val body = request.body.readUtf8()
            assertEquals("POST", request.method)
            assertEquals("/v1/qris/qr-codes", request.path)
            assertEquals("attempt-1", request.getHeader("Idempotency-Key"))
            assertEquals("""{"qrisHostId":2,"baseAmount":15000,"merchantTrxId":"APP-attempt-1"}""", body)
            // Canonical string: METHOD, path tanpa query, deviceId, timestamp header, nonce header, sha256(body).
            assertEquals(
                listOf("POST", "/v1/qris/qr-codes", "dev-1", request.getHeader("X-Timestamp"),
                    request.getHeader("X-Nonce"), sha256Hex(body)),
                signer.canonical!!.split("\n"),
            )
        }
    }

    @Test fun `generate retry with same attemptId resends identical key and body`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setResponseCode(503)
                .setBody("""{"error":{"code":"ACQUIRER_UNAVAILABLE","message":"down"}}"""))
            server.enqueue(MockResponse().setBody(qrBody))
            val first = repo.generate(2, BigDecimal("15000"), "attempt-1")
            val second = repo.generate(2, BigDecimal("15000"), "attempt-1")
            assertEquals("ACQUIRER_UNAVAILABLE", (first as ApiResult.Failure).error.code)
            assertTrue(second is ApiResult.Success)
            val r1 = server.takeRequest()
            val r2 = server.takeRequest()
            assertEquals(r1.getHeader("Idempotency-Key"), r2.getHeader("Idempotency-Key"))
            assertEquals(r1.body.readUtf8(), r2.body.readUtf8())
        }
    }

    @Test fun `generate with a new attemptId sends a new key`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody(qrBody))
            server.enqueue(MockResponse().setBody(qrBody))
            repo.generate(2, BigDecimal("15000"), "attempt-1")
            repo.generate(2, BigDecimal("15000"), "attempt-2")
            assertEquals("attempt-1", server.takeRequest().getHeader("Idempotency-Key"))
            assertEquals("attempt-2", server.takeRequest().getHeader("Idempotency-Key"))
        }
    }

    @Test fun `inquiry uses a fresh idempotency key on every call`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"INITIATED","baseAmount":15000}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"AUTHORIZED","baseAmount":15000,
                "rrn":"1","approvalCode":"2","paidAt":"2026-09-24T03:16:10Z","issuerName":null}}"""))
            val first = (repo.inquiry("tx-1") as ApiResult.Success).data
            val second = (repo.inquiry("tx-1") as ApiResult.Success).data
            assertEquals(QrisStatus.INITIATED, first.status)
            assertNull(first.rrn)
            assertTrue(second.status.isPaid)
            assertEquals("1", second.rrn)
            val r1 = server.takeRequest()
            val r2 = server.takeRequest()
            assertEquals("/v1/qris/inquiries", r1.path)
            assertEquals("""{"transactionId":"tx-1"}""", r1.body.readUtf8())
            assertNotNull(r1.getHeader("Idempotency-Key"))
            assertNotEquals(r1.getHeader("Idempotency-Key"), r2.getHeader("Idempotency-Key"))
        }
    }

    @Test fun `backend error code is surfaced from a 422 envelope`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setResponseCode(422)
                .setBody("""{"error":{"code":"AMOUNT_OUT_OF_RANGE","message":"x","details":{}},"meta":{}}"""))
            val result = repo.generate(2, BigDecimal("1"), "attempt-1")
            assertEquals("AMOUNT_OUT_OF_RANGE", (result as ApiResult.Failure).error.code)
            assertEquals(422, result.error.httpStatus)
        }
    }

    @Test fun `incomplete qr-codes response becomes RESPONSE_UNEXPECTED`() = runBlocking {
        withServer { server, repo, _ ->
            server.enqueue(MockResponse().setBody("""{"data":{"transactionId":"tx-1","status":"INITIATED"}}"""))
            val result = repo.generate(2, BigDecimal("15000"), "attempt-1")
            assertEquals("RESPONSE_UNEXPECTED", (result as ApiResult.Failure).error.code)
        }
    }
}
