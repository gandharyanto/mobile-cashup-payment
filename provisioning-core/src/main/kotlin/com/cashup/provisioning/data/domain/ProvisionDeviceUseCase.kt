package com.cashup.provisioning.domain

import android.util.Base64
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.Tr34ParsingException
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.crypto.keyCheckValue
import com.cashup.provisioning.crypto.keyCheckValueMatches
import com.cashup.provisioning.crypto.normalizeReportedKeyCheckValue
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.QrRedeemRequest

/** HTTP ceremony is kept here; screens only observe steps and the final outcome. */
class ProvisionDeviceUseCase(
    private val gateway: ProvisioningGateway,
    private val serialNumbers: SerialNumberProvider,
    private val keys: ProvisioningKeys,
    private val installer: TerminalKeyInstaller,
    private val state: ProvisioningStateRepository,
    private val deviceIdentity: DeviceIdentitySink = DeviceIdentitySink { },
    private val unwrapperFactory: (RsaUnwrapper) -> PackageUnwrapper = { PackageUnwrapper(it) },
    private val journal: ProvisioningJournal = ProvisioningJournal(),
) {
    val PURPOSES = setOf("TRACK", "AMOUNT", "PIN", "EMV")

    suspend operator fun invoke(rawChallengeCode: String, onStep: (ProvisioningStep) -> Unit = {}): ProvisioningOutcome {
        journal.clear()
        var step = ProvisioningStep.DETECT_DEVICE
        var identityPersisted = false
        val hadIdentity = state.identity() != null
        var installStarted = false
        try {
            fun advance(next: ProvisioningStep) { step = next; onStep(next); journal.start(next) }
            advance(ProvisioningStep.DETECT_DEVICE)
            val serial = serialNumbers.serialNumber()?.takeIf { it.isNotBlank() }
                ?: return fail(DEVICE_UNKNOWN, "Nomor seri perangkat tidak tersedia")
            val token = rawChallengeCode.filter(Char::isLetterOrDigit)
            if (token.isEmpty()) return fail(CHALLENGE_EMPTY, "Kode provisioning kosong")

            advance(ProvisioningStep.GENERATE_KEYS)
            val rsa = keys.ensureRsaKeyPair()
            val devicePublicKey = keys.ensureEd25519KeyPair()
            val krdCsr = keys.krdCsr()

            advance(ProvisioningStep.REDEEM)
            val redeemed = when (val response = gateway.redeem(QrRedeemRequest(
                qrToken = token,
                serialNumber = serial,
                rsaPublicKey = rsa.publicKeySpkiBase64,
                devicePublicKey = devicePublicKey,
                purposes = PURPOSES,
                krdCsr = krdCsr,
            ))) {
                is ApiResult.Success -> response.data
                is ApiResult.Failure -> { if (!hadIdentity) rollbackAll(); return fail(response.error.code, response.error.message) }
            }
            if (redeemed.deviceId.isBlank()) {
                if (!hadIdentity) rollbackAll()
                return fail(PROTOCOL_VIOLATION, "Redeem tanpa deviceId")
            }
            state.saveIdentity(IdentityState(serial, redeemed.deviceId, redeemed.credentialKeyVersion,
                redeemed.certificateChain ?: emptyList(), devicePublicKey))
            identityPersisted = true

            if (!redeemed.dukptProvisioningRequired) {
                return ProvisioningOutcome.Success(serial, "", emptyList(), journal.render(), identityOnly = true)
            }
            val orderId = redeemed.orderId ?: return fail(PROTOCOL_VIOLATION, "Redeem tanpa orderId")
            val activationToken = redeemed.activationToken ?: return fail(PROTOCOL_VIOLATION, "Redeem tanpa activationToken")
            val expectedVersion = redeemed.keySetVersion ?: return fail(PROTOCOL_VIOLATION, "Redeem tanpa keySetVersion")

            advance(ProvisioningStep.DOWNLOAD_PACKAGE)
            val encrypted = when (val response = gateway.downloadKeyPackage(KeyPackageRequest(orderId, activationToken))) {
                is ApiResult.Success -> response.data
                is ApiResult.Failure -> { return fail(response.error.code, response.error.message) }
            }
            if (encrypted.orderId != orderId || encrypted.deviceId != redeemed.deviceId ||
                encrypted.keySetVersion != expectedVersion || encrypted.algorithm !in setOf("TDES_DUKPT", "TR34_2019")) {
                return fail(PACKAGE_INVALID, "Binding atau algoritma paket key tidak cocok")
            }

            advance(ProvisioningStep.UNWRAP_PACKAGE)
            val materials = try {
                if (encrypted.algorithm == "TR34_2019") keys.unwrapTr34(encrypted)
                else unwrapperFactory(keys.unwrapper()).unwrap(encrypted)
            }
            catch (e: PackageIntegrityException) {
                val code = if (e.message == "PACKAGE_SIGNATURE_INVALID") "PACKAGE_SIGNATURE_INVALID" else PACKAGE_INVALID
                return fail(code, e.message ?: "Paket key tidak sah")
            }
            catch (e: Tr34ParsingException) {
                return fail(PACKAGE_INVALID, e.message ?: "Key token TR-34 tidak valid")
            }
            if (materials.map { it.purpose }.toSet() != PURPOSES) {
                materials.forEach { it.zeroize() }
                return fail(PACKAGE_INVALID, "Purpose paket key tidak lengkap")
            }
            val checkValues = try {
                materials.associate { material ->
                    val actual = keyCheckValue(material.ipek.copyOf())
                    val reported = if (encrypted.algorithm == "TR34_2019") {
                        val serverKcv = encrypted.tr34Materials?.get(material.purpose)?.kcv
                            ?: error("KCV TR-34 tidak tersedia untuk ${material.purpose}")
                        val normalized = normalizeReportedKeyCheckValue(serverKcv)
                        check(keyCheckValueMatches(actual, normalized)) {
                            "KCV key terminal tidak cocok untuk ${material.purpose}"
                        }
                        normalized
                    } else actual
                    material.purpose to reported
                }
            } catch (failure: Exception) {
                materials.forEach { it.zeroize() }
                return fail(PACKAGE_INVALID, failure.message ?: "KCV paket key tidak valid")
            }
            advance(ProvisioningStep.INSTALL_KEYS)
            installStarted = true
            val installed = try { installer.install(materials) } finally { materials.forEach { it.zeroize() } }
            if (installed is TerminalKeyInstallResult.Failed) {
                rollbackDukpt()
                return fail(KEY_INSTALL_FAILED, installed.reason)
            }
            installed as TerminalKeyInstallResult.Installed

            val proof = "$orderId:$expectedVersion:${encrypted.activationChallenge}".toByteArray(Charsets.UTF_8)
            val signature = Base64.encodeToString(keys.signActivation(proof), Base64.NO_WRAP)
            advance(ProvisioningStep.ACTIVATE)
            val activated = when (val response = gateway.activate(orderId, ActivateRequest(activationToken, checkValues, signature))) {
                is ApiResult.Success -> response.data
                is ApiResult.Failure -> { rollbackDukpt(); return fail(response.error.code, response.error.message) }
            }
            if (activated.orderId != orderId || activated.deviceId != redeemed.deviceId || activated.keySetVersion != expectedVersion) {
                rollbackDukpt()
                return fail(PROTOCOL_VIOLATION, "Respons aktivasi tidak cocok dengan order")
            }
            advance(ProvisioningStep.PERSIST_STATE)
            state.saveDukpt(DukptState(redeemed.deviceId, activated.keySetId, activated.keySetVersion,
                installed.outcomes.associate { it.purpose to it.backing.name }))
            return ProvisioningOutcome.Success(serial, orderId, installed.outcomes, journal.render())
        } catch (failure: Exception) {
            if (installStarted) rollbackDukpt() else if (!identityPersisted && !hadIdentity) rollbackAll()
            return fail(UNEXPECTED_ERROR, failure.message ?: "Kesalahan tak terduga pada $step")
        } finally {
            deviceIdentity.setInFlightSerial(null)
        }
    }

    private suspend fun rollbackDukpt() {
        runCatching { installer.wipe() }
        runCatching { state.clearDukpt() }
    }

    private suspend fun rollbackAll() {
        rollbackDukpt()
        runCatching { state.clearIdentity() }
        runCatching { keys.clearAll() }
    }

    private fun fail(code: String, message: String) = ProvisioningOutcome.Failure(code, message, journal.render())

    companion object {
        const val DEVICE_UNKNOWN = "DEVICE_UNKNOWN"
        const val CHALLENGE_EMPTY = "CHALLENGE_EMPTY"
        const val PACKAGE_INVALID = "PACKAGE_INVALID"
        const val KEY_INSTALL_FAILED = "KEY_INSTALL_FAILED"
        const val PROTOCOL_VIOLATION = "PROTOCOL_VIOLATION"
        const val UNEXPECTED_ERROR = "UNEXPECTED_ERROR"
    }
}
