package com.cashup.provisioning.data

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.ProvisioningHttp
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.signing.DeviceSigner
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProvisioningRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ProvisioningRepository

    private val signer = object : DeviceSigner {
        override fun deviceId(): String = "PAX-A920-0012938"
        override fun sign(canonicalBytes: ByteArray): ByteArray = ByteArray(64) { 7 }
    }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
        repository = ProvisioningHttp.create(server.url("/").toString(), signer)
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `redeem posts to the agreed path and is not signed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","activationToken":"tok-1"}}"""
            )
        )

        val result = repository.redeem(
            QrRedeemRequest("ABCD-1234", "PAX-A920-0012938", "rsa-spki", "eddsa-raw")
        )

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/terminal-key-provisioning/qr-redeem", recorded.path)
        assertNotNull(recorded.getHeader("X-Timestamp"))
        assertNotNull(recorded.getHeader("X-Correlation-Id"))
        assertNull("qr-redeem must not be signed", recorded.getHeader("X-Signature"))

        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""challengeCode":"ABCD-1234""""))
        assertTrue(body.contains(""""eddsaPublicKey":"eddsa-raw""""))

        assertEquals("o-1", (result as ApiResult.Success).data.orderId)
    }

    @Test
    fun `downloadKeyPackage posts to the order path and is signed`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}"""
            )
        )

        val result = repository.downloadKeyPackage(KeyPackageRequest("o-1", "tok-1"))

        val recorded = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/package", recorded.path)
        assertEquals("PAX-A920-0012938", recorded.getHeader("X-Device-Id"))
        assertNotNull(recorded.getHeader("X-Signature"))
        assertNotNull(recorded.getHeader("X-Nonce"))

        assertEquals("d3JhcHBlZA", (result as ApiResult.Success).data.wrappedPackageKey)
    }

    @Test
    fun `activate sends the key check values it was given`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"status":"ACTIVE"}}""")
        )

        val result = repository.activate(
            "o-1",
            ActivateRequest("tok-1", mapOf("PIN" to "A1B2C3", "TRACK" to "D4E5F6")),
        )

        val recorded = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/orders/o-1/activate", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""PIN":"A1B2C3""""))
        assertTrue(body.contains(""""TRACK":"D4E5F6""""))

        assertEquals("ACTIVE", (result as ApiResult.Success).data.status)
    }

    @Test
    fun `a backend error code survives to the caller`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(410).setBody(
                """{"error":{"code":"PROVISIONING_TOKEN_INVALID","message":"Kode QR kedaluwarsa"}}"""
            )
        )

        val result = repository.redeem(QrRedeemRequest("X", "S", "r", "e"))

        assertEquals("PROVISIONING_TOKEN_INVALID", (result as ApiResult.Failure).error.code)
    }
}