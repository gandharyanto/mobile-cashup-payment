package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiResult
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.domain.ProvisioningStateRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SignedProvisioningHeadersTest {
    private class State : ProvisioningStateRepository {
        var record: IdentityState? = null
        override fun identity() = record
        override fun saveIdentity(state: IdentityState) { record = state }
        override fun clearIdentity() { record = null }
        override fun dukpt(): DukptState? = null
        override fun saveDukpt(state: DukptState) = Unit
        override fun clearDukpt() = Unit
    }

    @Test fun `package is signed with backend device id after redeem`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val state = State()
            val signer = StoredDeviceSigner(state) { ByteArray(64) { 7 } }
            val client = ProvisioningHttp.create(server.url("/").toString(), signer)
            val refused = client.downloadKeyPackage(KeyPackageRequest("o-1", "token"))
            assertTrue(refused is ApiResult.Failure)
            assertEquals(0, server.requestCount)

            state.saveIdentity(IdentityState("SN-1", "backend-uuid", 1))
            server.enqueue(MockResponse().setBody("""{"data":{"orderId":"o-1","deviceId":"backend-uuid","keySetVersion":1,"algorithm":"TDES_DUKPT","activationChallenge":"x"}}"""))
            assertTrue(client.downloadKeyPackage(KeyPackageRequest("o-1", "token")) is ApiResult.Success)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("token", request.getHeader("X-Activation-Token"))
            assertEquals("backend-uuid", request.getHeader("X-Device-Id"))
            assertNotNull(request.getHeader("X-Signature"))
        } finally { server.shutdown() }
    }
}
