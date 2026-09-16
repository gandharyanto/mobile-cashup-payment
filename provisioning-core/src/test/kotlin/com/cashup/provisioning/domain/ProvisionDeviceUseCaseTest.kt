package com.cashup.provisioning.domain

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import com.cashup.devicesdk.fake.FakeTerminalKeyInstaller
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyLocation
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.ProvisioningState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisionDeviceUseCaseTest {

    private class FakeGateway(
        var redeemResult: ApiResult<QrRedeemResponse> =
            ApiResult.Success(QrRedeemResponse("o-1", "tok-1")),
        var packageResult: ApiResult<KeyPackageResponse> =
            ApiResult.Success(KeyPackageResponse("o-1", "d3JhcHBlZA==")),
        var activateResult: ApiResult<ActivateResponse> =
            ApiResult.Success(ActivateResponse("ACTIVE")),
    ) : ProvisioningGateway {
        var redeemRequest: QrRedeemRequest? = null
        var activateRequest: ActivateRequest? = null

        override suspend fun redeem(request: QrRedeemRequest) =
            redeemResult.also { redeemRequest = request }

        override suspend fun downloadKeyPackage(request: KeyPackageRequest) = packageResult

        override suspend fun activate(orderId: String, request: ActivateRequest) =
            activateResult.also { activateRequest = request }
    }

    private class FakeKeys : ProvisioningKeys {
        var clearCount = 0
        override fun ensureRsaKeyPair() = RsaKeyInfo("rsa-spki", RsaKeyLocation.ANDROID_KEYSTORE)
        override fun ensureEd25519KeyPair() = "eddsa-raw"
        override fun unwrapper() = RsaUnwrapper { it }
        override fun clearAll() { clearCount++ }
    }

    private class FakeState : ProvisioningStateRepository {
        var saved: ProvisioningState? = null
        var clearCount = 0
        override fun current() = saved
        override fun save(state: ProvisioningState) { saved = state }
        override fun clear() { saved = null; clearCount++ }
    }

    private fun materials() = listOf(
        TerminalKeyMaterial("PIN", ByteArray(16) { 1 }, ByteArray(10) { 2 }),
        TerminalKeyMaterial("TRACK", ByteArray(16) { 3 }, ByteArray(10) { 4 }),
    )

    private fun useCase(
        gateway: ProvisioningGateway = FakeGateway(),
        keys: ProvisioningKeys = FakeKeys(),
        state: ProvisioningStateRepository = FakeState(),
        installer: FakeTerminalKeyInstaller = FakeTerminalKeyInstaller(),
        serial: String? = "PAX-A920-0012938",
        unwrap: () -> List<TerminalKeyMaterial> = ::materials,
    ) = ProvisionDeviceUseCase(
        gateway = gateway,
        serialNumbers = FakeSerialNumberProvider(serial),
        keys = keys,
        installer = installer,
        state = state,
        unwrapperFactory = {
            object : PackageUnwrapper(RsaUnwrapper { it }) {
                override fun unwrap(wrappedPackageKeyBase64: String) = unwrap()
            }
        },
    )

    @Test
    fun `a clean run installs the keys and reports where each landed`() = runTest {
        val state = FakeState()
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, state = state)("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)
        val success = outcome as ProvisioningOutcome.Success
        assertEquals("PAX-A920-0012938", success.serialNumber)
        assertEquals("o-1", success.orderId)
        assertEquals(
            KeyBacking.VENDOR_SECURE_MODULE,
            success.installed.single { it.purpose == "PIN" }.backing,
        )
        assertEquals("PAX-A920-0012938", state.saved?.serialNumber)
    }

    @Test
    fun `redeem carries the serial number and both public keys`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        val request = gateway.redeemRequest!!
        assertEquals("PAX-A920-0012938", request.serialNumber)
        assertEquals("rsa-spki", request.rsaPublicKey)
        assertEquals("eddsa-raw", request.eddsaPublicKey)
    }

    @Test
    fun `the challenge code is normalized before it is sent`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("  abcd-1234 ")

        // Server menormalkan dengan cara yang sama, jadi input manual bertanda
        // hubung atau berspasi tetap cocok dengan hasil scan.
        assertEquals("abcd1234", gateway.redeemRequest!!.challengeCode)
    }

    @Test
    fun `activate sends a KCV for every purpose that was installed`() = runTest {
        val gateway = FakeGateway()

        useCase(gateway = gateway)("ABCD-1234")

        assertEquals(setOf("PIN", "TRACK"), gateway.activateRequest!!.keyCheckValues.keys)
    }

    @Test
    fun `an unknown device never reaches the network`() = runTest {
        val gateway = FakeGateway()

        val outcome = useCase(gateway = gateway, serial = null)("ABCD-1234")

        assertEquals("DEVICE_UNKNOWN", (outcome as ProvisioningOutcome.Failure).code)
        assertNull(gateway.redeemRequest)
    }

    @Test
    fun `a rejected challenge code keeps the backend error code`() = runTest {
        val gateway = FakeGateway(
            redeemResult = ApiResult.Failure(
                ApiError("PROVISIONING_TOKEN_INVALID", "Kode QR kedaluwarsa", 410)
            )
        )

        val outcome = useCase(gateway = gateway)("ABCD-1234")

        assertEquals("PROVISIONING_TOKEN_INVALID", (outcome as ProvisioningOutcome.Failure).code)
    }

    @Test
    fun `a failed activate wipes every key that was already installed`() = runTest {
        val installer = FakeTerminalKeyInstaller()
        val keys = FakeKeys()
        val state = FakeState()
        val gateway = FakeGateway(
            activateResult = ApiResult.Failure(ApiError("TERMINAL_INACTIVE", "Tidak aktif", 403))
        )

        val outcome = useCase(gateway = gateway, keys = keys, state = state, installer = installer)("ABCD-1234")

        // Ini inti aturan atomic: activate gagal SETELAH key terpasang, jadi
        // rollback harus benar-benar mencabutnya -- bukan meninggalkan device
        // dengan key yang backend tidak tahu keberadaannya.
        assertEquals("TERMINAL_INACTIVE", (outcome as ProvisioningOutcome.Failure).code)
        assertEquals(1, installer.wipeCount)
        assertEquals(1, keys.clearCount)
        assertNull(state.saved)
    }

    @Test
    fun `a corrupted key package rolls back before anything is installed`() = runTest {
        val installer = FakeTerminalKeyInstaller()
        val keys = FakeKeys()

        val outcome = useCase(keys = keys, installer = installer, unwrap = {
            throw PackageIntegrityException("KCV tidak cocok untuk purpose PIN")
        })("ABCD-1234")

        assertEquals("PACKAGE_INVALID", (outcome as ProvisioningOutcome.Failure).code)
        assertTrue(installer.installedPurposes.isEmpty())
        assertEquals(1, installer.wipeCount)
        assertEquals(1, keys.clearCount)
    }

    @Test
    fun `a refused key installation rolls back and names the purpose`() = runTest {
        val installer = FakeTerminalKeyInstaller(failOnPurpose = "TRACK")
        val state = FakeState()

        val outcome = useCase(installer = installer, state = state)("ABCD-1234")

        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals("KEY_INSTALL_FAILED", failure.code)
        assertTrue(failure.message, failure.message.contains("TRACK"))
        assertEquals(1, installer.wipeCount)
        assertNull(state.saved)
    }

    /**
     * Wraps a [FakeTerminalKeyInstaller] so `wipe()` still counts toward it
     * (rollback verification), while `install()` throws a plain
     * [RuntimeException] instead of returning [TerminalKeyInstallResult.Failed]
     * -- simulating the vendor installer binder dying mid-call.
     */
    private class ThrowingInstaller(private val delegate: FakeTerminalKeyInstaller) : TerminalKeyInstaller {
        override suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult {
            throw RuntimeException("vendor binder died")
        }

        override suspend fun wipe() = delegate.wipe()
    }

    @Test
    fun `an unexpected exception from the installer still rolls back and returns a Failure`() = runTest {
        val delegate = FakeTerminalKeyInstaller()
        val keys = FakeKeys()
        val state = FakeState()
        val gateway = FakeGateway()

        val outcome = ProvisionDeviceUseCase(
            gateway = gateway,
            serialNumbers = FakeSerialNumberProvider("PAX-A920-0012938"),
            keys = keys,
            installer = ThrowingInstaller(delegate),
            state = state,
            unwrapperFactory = {
                object : PackageUnwrapper(RsaUnwrapper { it }) {
                    override fun unwrap(wrappedPackageKeyBase64: String) = materials()
                }
            },
        )("ABCD-1234")

        // Ini inti Temuan 1: exception yang bukan PackageIntegrityException
        // tidak boleh lolos dari invoke() begitu saja -- ia harus tetap
        // melewati rollback dan kembali sebagai Failure.
        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Failure)
        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals("UNEXPECTED_ERROR", failure.code)
        assertEquals(1, delegate.wipeCount)
        assertEquals(1, keys.clearCount)
        assertNull(state.saved)
    }

    @Test
    fun `an unexpected exception from the unwrapper still rolls back and returns a Failure`() = runTest {
        val installer = FakeTerminalKeyInstaller()
        val keys = FakeKeys()
        val state = FakeState()

        val outcome = useCase(
            keys = keys,
            state = state,
            installer = installer,
            unwrap = { throw RuntimeException("malformed IPEK, keyCheckValue blew up") },
        )("ABCD-1234")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Failure)
        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals("UNEXPECTED_ERROR", failure.code)
        assertTrue(installer.installedPurposes.isEmpty())
        assertEquals(1, installer.wipeCount)
        assertEquals(1, keys.clearCount)
        assertNull(state.saved)
    }

    @Test
    fun `the journal records every step and never leaks key bytes`() = runTest {
        val outcome = useCase()("ABCD-1234")

        val journal = (outcome as ProvisioningOutcome.Success).journalText
        listOf("DETECT_DEVICE", "GENERATE_KEYS", "REDEEM", "DOWNLOAD_PACKAGE", "UNWRAP_PACKAGE", "INSTALL_KEYS", "ACTIVATE", "PERSIST_STATE")
            .forEach { assertTrue("missing $it", journal.contains(it)) }

        // IPEK di tes ini seluruhnya byte 0x01; kalau pernah dirender mentah,
        // pola itu akan muncul.
        assertTrue(journal, !journal.contains("01010101"))
    }
}