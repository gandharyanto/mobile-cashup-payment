package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import com.cashup.devicesdk.fake.FakeTerminalKeyInstaller
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyLocation
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.ActivateResponse
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.provisioning.data.remote.QrRedeemRequest
import com.cashup.provisioning.data.remote.QrRedeemResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProvisionDeviceUseCaseTest {
    private class State : ProvisioningStateRepository {
        var identityRecord: IdentityState? = null
        var dukptRecord: DukptState? = null
        override fun identity() = identityRecord
        override fun saveIdentity(state: IdentityState) { identityRecord = state }
        override fun clearIdentity() { identityRecord = null }
        override fun dukpt() = dukptRecord
        override fun saveDukpt(state: DukptState) { dukptRecord = state }
        override fun clearDukpt() { dukptRecord = null }
    }

    private class Keys : ProvisioningKeys {
        var clears = 0
        override fun ensureRsaKeyPair() = RsaKeyInfo("rsa", RsaKeyLocation.SOFTWARE)
        override fun ensureEd25519KeyPair() = "device-key"
        override fun certificateChain() = listOf("cert")
        override fun signActivation(bytes: ByteArray) = bytes
        override fun unwrapper() = RsaUnwrapper { it }
        override fun clearAll() { clears++ }
    }

    private class Gateway : ProvisioningGateway {
        var redeemResult: ApiResult<QrRedeemResponse> = ApiResult.Success(
            QrRedeemResponse(deviceId = "uuid-1", credentialKeyVersion = 2, dukptProvisioningRequired = true,
                orderId = "o-1", activationToken = "secret", keySetVersion = 3, status = "PENDING"))
        var packageResult: ApiResult<KeyPackageResponse> = ApiResult.Success(
            KeyPackageResponse("o-1", "uuid-1", 3, "TDES_DUKPT", "challenge"))
        var activateResult: ApiResult<ActivateResponse> = ApiResult.Success(
            ActivateResponse("o-1", "uuid-1", 7, 3, "ACTIVE"))
        var redeemRequest: QrRedeemRequest? = null
        var packageRequest: KeyPackageRequest? = null
        var activateRequest: ActivateRequest? = null
        override suspend fun redeem(request: QrRedeemRequest): ApiResult<QrRedeemResponse> {
            redeemRequest = request; return redeemResult
        }
        override suspend fun downloadKeyPackage(request: KeyPackageRequest): ApiResult<KeyPackageResponse> {
            packageRequest = request; return packageResult
        }
        override suspend fun activate(orderId: String, request: ActivateRequest): ApiResult<ActivateResponse> {
            activateRequest = request; return activateResult
        }
    }

    private fun useCase(gateway: Gateway, state: State, keys: Keys, installer: FakeTerminalKeyInstaller) =
        ProvisionDeviceUseCase(gateway, FakeSerialNumberProvider("SN-1"), keys, installer, state,
            unwrapperFactory = { object : PackageUnwrapper(RsaUnwrapper { it }) {
                override fun unwrap(packageResponse: KeyPackageResponse) = listOf("TRACK", "AMOUNT", "PIN", "EMV")
                    .map { TerminalKeyMaterial(it, ByteArray(16) { 1 }, ByteArray(10) { 2 }) }
            } })

    @Test fun `redeem persists backend identity before signed package call`() = runBlocking {
        val gateway = Gateway()
        val state = State()
        val keys = Keys()
        val installer = FakeTerminalKeyInstaller()
        val outcome = useCase(gateway, state, keys, installer)("AB-CD")
        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)
        assertEquals("ABCD", gateway.redeemRequest?.qrToken)
        assertEquals(setOf("TRACK", "AMOUNT", "PIN", "EMV"), gateway.redeemRequest?.purposes)
        assertEquals(listOf("cert"), gateway.redeemRequest?.deviceCertificateChain)
        assertEquals("uuid-1", state.identity()?.deviceId)
        assertEquals("uuid-1", state.dukpt()?.deviceId)
        assertEquals("secret", gateway.packageRequest?.activationToken)
        assertEquals("o-1:3:challenge", String(android.util.Base64.decode(gateway.activateRequest!!.deviceSignature, android.util.Base64.DEFAULT)))
    }

    @Test fun `a PIN key that did not reach the vendor secure module fails before activation`() = runBlocking {
        val gateway = Gateway()
        val state = State()
        // No vendor slot: every purpose, PIN included, lands in the vault only.
        val installer = FakeTerminalKeyInstaller(vendorSlotPurpose = null)

        val outcome = useCase(gateway, state, Keys(), installer)("AB-CD")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Failure)
        assertEquals(ProvisionDeviceUseCase.KEY_INSTALL_FAILED, (outcome as ProvisioningOutcome.Failure).code)
        assertNull("activation must not be reached", gateway.activateRequest)
        assertNull(state.dukpt())
        assertEquals(1, installer.wipeCount)
    }

    private fun assertRejectedBeforeActivate(outcome: ProvisioningOutcome, gateway: Gateway, state: State, wipes: Int) {
        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Failure)
        val failure = outcome as ProvisioningOutcome.Failure
        assertEquals(ProvisionDeviceUseCase.KEY_INSTALL_FAILED, failure.code)
        assertNull("activation must not be reached", gateway.activateRequest)
        assertNull(state.dukpt())
        assertEquals(1, wipes)
        // Rejection text carries no key bytes (fake material is 0x01 * 16 / 0x02 * 10).
        listOf("01010101", "0202020202", "[B@").forEach {
            assertFalse("message leaked $it", failure.message.contains(it))
            assertFalse("journal leaked $it", failure.journalText.contains(it))
        }
    }

    @Test fun `installed result with no PIN outcome is rejected before activation`() = runBlocking {
        val gateway = Gateway()
        val state = State()
        val installer = object : com.cashup.devicesdk.TerminalKeyInstaller {
            var wipes = 0
            override suspend fun install(materials: List<TerminalKeyMaterial>) =
                com.cashup.devicesdk.TerminalKeyInstallResult.Installed(
                    listOf("TRACK", "AMOUNT", "EMV").map {
                        com.cashup.devicesdk.KeyInstallOutcome(it, com.cashup.devicesdk.KeyBacking.VENDOR_SECURE_MODULE)
                    })
            override suspend fun wipe() { wipes++ }
        }
        val uc = ProvisionDeviceUseCase(gateway, FakeSerialNumberProvider("SN-1"), Keys(), installer, state,
            unwrapperFactory = { object : PackageUnwrapper(RsaUnwrapper { it }) {
                override fun unwrap(packageResponse: KeyPackageResponse) = listOf("TRACK", "AMOUNT", "PIN", "EMV")
                    .map { TerminalKeyMaterial(it, ByteArray(16) { 1 }, ByteArray(10) { 2 }) }
            } })

        assertRejectedBeforeActivate(uc("AB-CD"), gateway, state, installer.wipes)
    }

    @Test fun `a non PIN purpose in the vendor slot while PIN is vault only is rejected`() = runBlocking {
        val gateway = Gateway()
        val state = State()
        val installer = FakeTerminalKeyInstaller(vendorSlotPurpose = "EMV")

        val outcome = useCase(gateway, state, Keys(), installer)("AB-CD")

        assertRejectedBeforeActivate(outcome, gateway, state, installer.wipeCount)
    }

    @Test fun `PIN in the vendor slot activates and persists the backing`() = runBlocking {
        val gateway = Gateway()
        val state = State()
        val installer = FakeTerminalKeyInstaller(vendorSlotPurpose = "PIN")

        val outcome = useCase(gateway, state, Keys(), installer)("AB-CD")

        assertTrue(outcome.toString(), outcome is ProvisioningOutcome.Success)
        assertNotNull(gateway.activateRequest)
        assertEquals("VENDOR_SECURE_MODULE", state.dukpt()?.backings?.get("PIN"))
        assertEquals(0, installer.wipeCount)
    }

    @Test fun `identity only refresh skips package and keeps active dukpt`() = runBlocking {
        val gateway = Gateway().apply { redeemResult = ApiResult.Success(
            QrRedeemResponse(deviceId = "uuid-1", credentialKeyVersion = 5,
                dukptProvisioningRequired = false, status = "ACTIVE")) }
        val state = State().apply { dukptRecord = DukptState("uuid-1", 7, 3, emptyMap()) }
        val outcome = useCase(gateway, state, Keys(), FakeTerminalKeyInstaller())("QR")
        assertTrue(outcome is ProvisioningOutcome.Success)
        assertNull(gateway.packageRequest)
        assertEquals(7L, state.dukpt()?.keySetId)
        assertEquals(5, state.identity()?.credentialKeyVersion)
    }
}
