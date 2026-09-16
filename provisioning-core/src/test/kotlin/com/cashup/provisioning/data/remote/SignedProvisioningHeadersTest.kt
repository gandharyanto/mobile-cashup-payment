package com.cashup.provisioning.data.remote

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import com.cashup.devicesdk.fake.FakeTerminalKeyInstaller
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyLocation
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.ProvisioningState
import com.cashup.provisioning.domain.ProvisionDeviceUseCase
import com.cashup.provisioning.domain.ProvisioningKeys
import com.cashup.provisioning.domain.ProvisioningOutcome
import com.cashup.provisioning.domain.ProvisioningStateRepository
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

/**
 * Regresi untuk temuan Critical 1: `X-Device-Id` tidak pernah tersedia selama
 * ceremony, sehingga J1 mustahil selesai di device bersih.
 *
 * Tes-tes lain menyetub tepat di seam yang rusak — mereka memakai
 * `ProvisioningGateway` palsu (jadi tidak pernah menyentuh `SigningInterceptor`)
 * atau `DeviceSigner` palsu yang mengembalikan nomor seri hardcoded (jadi tidak
 * pernah menyentuh `StoredDeviceSigner`). Berkas ini sengaja tidak menyetub
 * keduanya: [StoredDeviceSigner] asli, [ProvisioningHttp.create] asli, dan
 * [ProvisionDeviceUseCase] asli, dijalankan dari state repository yang KOSONG
 * seperti device yang belum pernah diprovisioning.
 *
 * Tidak butuh Robolectric: [StoredDeviceSigner] dirakit lewat konstruktor
 * JVM-nya (repository + lambda penandatangan), sehingga Android Keystore tidak
 * ikut terseret.
 */
class SignedProvisioningHeadersTest {

    private lateinit var server: MockWebServer

    /** Kosong di awal, persis seperti device yang belum terprovisioning. */
    private class InMemoryState : ProvisioningStateRepository {
        var saved: ProvisioningState? = null
        override fun current() = saved
        override fun save(state: ProvisioningState) { saved = state }
        override fun clear() { saved = null }
    }

    private class FakeKeys : ProvisioningKeys {
        var clearCount = 0
        override fun ensureRsaKeyPair() = RsaKeyInfo("rsa-spki", RsaKeyLocation.ANDROID_KEYSTORE)
        override fun ensureEd25519KeyPair() = "eddsa-raw"
        override fun unwrapper() = RsaUnwrapper { it }
        override fun clearAll() { clearCount++ }
    }

    private val state = InMemoryState()

    /**
     * Penandatangan asli. Hanya operasi Ed25519-nya yang diganti lambda —
     * `deviceId()`, yang justru merupakan inti temuan ini, tetap kode produksi.
     */
    private val signer = StoredDeviceSigner(state) { ByteArray(64) { 7 } }

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun repository() = ProvisioningHttp.create(server.url("/").toString(), signer)

    @Test
    fun `a signed call from an empty state is refused until the serial is announced`() = runBlocking {
        val repository = repository()

        // Persis keadaan yang membuat provisioning mustahil sebelum perbaikan:
        // state kosong, jadi tidak ada identitas untuk ditandatangani.
        assertNull(signer.deviceId())
        val refused = repository.downloadKeyPackage(KeyPackageRequest("o-1", "tok-1"))
        assertTrue(refused.toString(), refused is ApiResult.Failure)
        assertEquals(0, server.requestCount)

        // Nomor seri sudah diketahui sejak langkah 1 ceremony; mengumumkannya
        // membuat request yang sama bisa ditandatangani, tanpa menunggu
        // `state.save` di langkah terakhir.
        signer.setInFlightSerial("PAX-A920-0012938")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}""")
        )

        val result = repository.downloadKeyPackage(KeyPackageRequest("o-1", "tok-1"))

        assertTrue(result.toString(), result is ApiResult.Success)
        val recorded = server.takeRequest()
        assertEquals("PAX-A920-0012938", recorded.getHeader("X-Device-Id"))
        assertNotNull(recorded.getHeader("X-Timestamp"))
        assertNotNull(recorded.getHeader("X-Nonce"))
        assertNotNull(recorded.getHeader("X-Signature"))
    }

    @Test
    fun `the whole ceremony signs every signed call on a device that was never provisioned`() = runBlocking {
        val repository = repository()
        val keys = FakeKeys()
        val installer = FakeTerminalKeyInstaller()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1","activationToken":"tok-1"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"data":{"status":"ACTIVE"}}""")
        )

        val outcome = ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = keys,
            installer = installer,
            state = state,
            deviceIdentity = signer,
            unwrapperFactory = {
                object : PackageUnwrapper(RsaUnwrapper { it }) {
                    override fun unwrap(wrappedPackageKeyBase64: String) = listOf(
                        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
                    )
                }
            },
        )("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)

        // 1. qr-redeem -- TIDAK ditandatangani, backend belum kenal key device.
        val redeem = server.takeRequest()
        assertEquals("/v1/terminal-key-provisioning/qr-redeem", redeem.path)
        assertNull(redeem.getHeader("X-Signature"))

        // 2-3. Keduanya ditandatangani, dan keduanya terjadi SEBELUM
        //      `state.save`. Inilah yang tidak pernah bisa berjalan sebelum
        //      perbaikan ini.
        listOf(
            "/v1/terminal-key-provisioning/orders/o-1/package",
            "/v1/terminal-key-provisioning/orders/o-1/activate",
        ).forEach { expectedPath ->
            val recorded = server.takeRequest()
            assertEquals(expectedPath, recorded.path)
            assertEquals(expectedPath, "PAX-A920-0012938", recorded.getHeader("X-Device-Id"))
            assertNotNull(expectedPath, recorded.getHeader("X-Timestamp"))
            assertNotNull(expectedPath, recorded.getHeader("X-Nonce"))
            assertNotNull(expectedPath, recorded.getHeader("X-Signature"))
        }

        assertEquals("PAX-A920-0012938", state.saved?.serialNumber)
        // Setelah ceremony selesai identitas datang dari state tersimpan, bukan
        // dari nilai in-flight.
        assertEquals("PAX-A920-0012938", signer.deviceId())
    }

    @Test
    fun `a rolled back ceremony leaves the device without an identity again`() = runBlocking {
        val repository = repository()

        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1","activationToken":"tok-1"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody("""{"data":{"orderId":"o-1","wrappedPackageKey":"d3JhcHBlZA"}}""")
        )
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setBody("""{"error":{"code":"TERMINAL_INACTIVE","message":"Tidak aktif"}}""")
        )

        val outcome = ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = FakeKeys(),
            installer = FakeTerminalKeyInstaller(),
            state = state,
            deviceIdentity = signer,
            unwrapperFactory = {
                object : PackageUnwrapper(RsaUnwrapper { it }) {
                    override fun unwrap(wrappedPackageKeyBase64: String) = listOf(
                        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
                    )
                }
            },
        )("ABCD-1234")

        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        // Rollback menjalankan state.clear() dan mencabut identitas in-flight:
        // device kembali tidak terprovisioning, jadi tidak boleh ada lagi
        // request bertanda tangan yang bisa dikirim atas namanya.
        assertNull(signer.deviceId())
    }
}
