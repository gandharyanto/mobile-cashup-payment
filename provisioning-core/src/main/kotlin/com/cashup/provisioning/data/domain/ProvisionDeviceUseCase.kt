package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.audit.Evidence
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.audit.ProvisioningStep
import com.cashup.provisioning.crypto.PackageIntegrityException
import com.cashup.provisioning.crypto.PackageUnwrapper
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.crypto.keyCheckValue
import com.cashup.provisioning.data.local.ProvisioningState
import com.cashup.provisioning.data.remote.ActivateRequest
import com.cashup.provisioning.data.remote.KeyPackageRequest
import com.cashup.provisioning.data.remote.QrRedeemRequest

/**
 * Ceremony provisioning J1, sepuluh langkah, atomic.
 *
 * Aturan yang mengikat seluruh kelas ini: **gagal di langkah mana pun
 * mengembalikan device ke keadaan belum terprovisioning.** Tidak ada state
 * setengah jalan. Kegagalan sebagian — DUKPT terpasang tapi `activate` ditolak,
 * misalnya — akan meninggalkan terminal memegang key yang backend tidak tahu
 * keberadaannya, dan itu baru ketahuan saat transaksi pertama ditolak host.
 * Karena itu setiap jalur keluar yang bukan sukses melewati [rollback].
 *
 * Parameter [journal] **sementara** — alat bantu pelaporan tahap awal, dicabut
 * bersama package `audit/` sebelum produksi. Ia punya nilai default supaya
 * pencabutannya tidak menyentuh pemanggil mana pun.
 */
class ProvisionDeviceUseCase(
    private val gateway: ProvisioningGateway,
    private val serialNumbers: SerialNumberProvider,
    private val keys: ProvisioningKeys,
    private val installer: TerminalKeyInstaller,
    private val state: ProvisioningStateRepository,
    private val unwrapperFactory: (RsaUnwrapper) -> PackageUnwrapper = { PackageUnwrapper(it) },
    private val journal: ProvisioningJournal = ProvisioningJournal(),
) {

    suspend operator fun invoke(rawChallengeCode: String): ProvisioningOutcome {
        journal.clear()

        // 1. Nomor seri. Tanpa ini backend tidak punya identitas untuk device
        //    ini, jadi jaringan tidak perlu disentuh sama sekali.
        journal.start(ProvisioningStep.DETECT_DEVICE)
        val serialNumber = serialNumbers.serialNumber()
        if (serialNumber.isNullOrBlank()) {
            journal.failed(ProvisioningStep.DETECT_DEVICE, DEVICE_UNKNOWN)
            return fail(DEVICE_UNKNOWN, "Perangkat tidak dikenali SDK vendor mana pun")
        }
        journal.ok(ProvisioningStep.DETECT_DEVICE, mapOf("serialNumber" to serialNumber))

        // 2. Keypair. Idempoten: percobaan ulang setelah gagal memakai key yang
        //    sama, sehingga public key yang didaftarkan tetap konsisten.
        journal.start(ProvisioningStep.GENERATE_KEYS)
        val rsa = keys.ensureRsaKeyPair()
        val eddsaPublicKey = keys.ensureEd25519KeyPair()
        journal.ok(
            ProvisioningStep.GENERATE_KEYS,
            mapOf(
                "rsa.location" to rsa.location.name,
                "rsa.publicKey" to Evidence.publicKey(rsa.publicKeySpkiBase64),
                "eddsa.publicKey" to Evidence.publicKey(eddsaPublicKey),
            ),
        )

        // 3. Kode QR. Dinormalisasi sama seperti di sisi server, supaya input
        //    manual bertanda hubung atau berspasi tetap cocok dengan hasil scan.
        val challengeCode = rawChallengeCode.filter(Char::isLetterOrDigit)
        if (challengeCode.isEmpty()) {
            journal.failed(ProvisioningStep.SCAN_QR, CHALLENGE_EMPTY)
            return fail(CHALLENGE_EMPTY, "Kode provisioning kosong")
        }
        journal.ok(
            ProvisioningStep.SCAN_QR,
            mapOf(
                "challengeCode.raw" to Evidence.token(rawChallengeCode),
                "challengeCode.normalized" to Evidence.token(challengeCode),
            ),
        )

        // 4. Redeem. Satu-satunya panggilan yang tidak ditandatangani.
        journal.start(ProvisioningStep.REDEEM)
        val redeemed = when (
            val result = gateway.redeem(
                QrRedeemRequest(
                    challengeCode = challengeCode,
                    serialNumber = serialNumber,
                    rsaPublicKey = rsa.publicKeySpkiBase64,
                    eddsaPublicKey = eddsaPublicKey,
                )
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.REDEEM,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ),
                )
                return fail(result.error.code, result.error.message)
            }
        }
        journal.ok(
            ProvisioningStep.REDEEM,
            mapOf(
                "orderId" to Evidence.token(redeemed.orderId),
                "activationToken" to Evidence.token(redeemed.activationToken),
            ),
        )

        // Mulai di sini backend sudah menerbitkan order, jadi setiap kegagalan
        // harus melewati rollback.
        journal.start(ProvisioningStep.DOWNLOAD_PACKAGE)
        val keyPackage = when (
            val result = gateway.downloadKeyPackage(
                KeyPackageRequest(redeemed.orderId, redeemed.activationToken)
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.DOWNLOAD_PACKAGE,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ),
                )
                return rollbackAndFail(result.error.code, result.error.message)
            }
        }
        journal.ok(
            ProvisioningStep.DOWNLOAD_PACKAGE,
            mapOf(
                "orderId" to Evidence.token(keyPackage.orderId),
                // Ciphertext -- kelas 1, dicatat penuh. Hanya private key device
                // yang bisa membukanya, dan justru nilai inilah yang dibutuhkan
                // untuk mereproduksi kegagalan unwrap di luar terminal.
                "wrappedPackageKey" to Evidence.ciphertext(keyPackage.wrappedPackageKey),
                "appEddsaPublicKey" to (keyPackage.appEddsaPublicKey ?: "-"),
            ),
        )

        // 6-7. Buka paket dan verifikasi KCV tiap purpose. Ini satu-satunya
        //      kesempatan mendeteksi key rusak: setelah masuk modul vendor, IPEK
        //      tidak bisa dibaca kembali.
        journal.start(ProvisioningStep.UNWRAP_PACKAGE)
        val materials: List<TerminalKeyMaterial> = try {
            unwrapperFactory(keys.unwrapper()).unwrap(keyPackage.wrappedPackageKey)
        } catch (e: PackageIntegrityException) {
            journal.failed(
                ProvisioningStep.UNWRAP_PACKAGE,
                PACKAGE_INVALID,
                mapOf("message" to (e.message ?: "-")),
            )
            return rollbackAndFail(PACKAGE_INVALID, e.message ?: "Paket key tidak sah")
        }
        val checkValues = materials.associate { it.purpose to keyCheckValue(it.ipek.copyOf()) }
        journal.ok(
            ProvisioningStep.UNWRAP_PACKAGE,
            materials.associate { "${it.purpose}.ipek" to Evidence.secret(it.ipek) } +
                    materials.associate { "${it.purpose}.ksn" to Evidence.secret(it.ksn) },
        )
        journal.ok(ProvisioningStep.VERIFY_KCV, checkValues.mapKeys { "${it.key}.kcv" })

        // 8. Pasang. Modul vendor dulu untuk purpose yang dinominasikan, sisanya
        //    ke vault.
        journal.start(ProvisioningStep.INSTALL_KEYS)
        val installed: List<KeyInstallOutcome> = when (val result = installer.install(materials)) {
            is TerminalKeyInstallResult.Installed -> result.outcomes
            is TerminalKeyInstallResult.Failed -> {
                journal.failed(ProvisioningStep.INSTALL_KEYS, KEY_INSTALL_FAILED, mapOf("purpose" to result.purpose))
                return rollbackAndFail(
                    KEY_INSTALL_FAILED,
                    "Gagal memasang key untuk purpose ${result.purpose}: ${result.reason}",
                )
            }
        }
        materials.forEach { it.zeroize() }
        journal.ok(
            ProvisioningStep.INSTALL_KEYS,
            installed.associate { it.purpose to it.backing.name },
        )

        // 9. Activate. Mengirim KCV sebagai bukti ke backend.
        journal.start(ProvisioningStep.ACTIVATE)
        val activated = when (
            val result = gateway.activate(
                redeemed.orderId,
                ActivateRequest(redeemed.activationToken, checkValues),
            )
        ) {
            is ApiResult.Success -> result.data
            is ApiResult.Failure -> {
                journal.failed(
                    ProvisioningStep.ACTIVATE,
                    result.error.code,
                    mapOf(
                        "httpStatus" to (result.error.httpStatus?.toString() ?: "-"),
                        "message" to result.error.message,
                    ) + checkValues.mapKeys { "sent.${it.key}.kcv" },
                )
                return rollbackAndFail(result.error.code, result.error.message)
            }
        }
        journal.ok(ProvisioningStep.ACTIVATE, mapOf("status" to activated.status))

        // 10. Simpan. Baru di sini device dianggap terprovisioning.
        journal.start(ProvisioningStep.PERSIST_STATE)
        state.save(
            ProvisioningState(
                serialNumber = serialNumber,
                orderId = redeemed.orderId,
                backings = installed.associate { it.purpose to it.backing.name },
            )
        )
        journal.ok(ProvisioningStep.PERSIST_STATE)

        return ProvisioningOutcome.Success(
            serialNumber = serialNumber,
            orderId = redeemed.orderId,
            installed = installed,
            journalText = journal.render(),
        )
    }

    /**
     * Mengembalikan device ke keadaan belum terprovisioning.
     *
     * Ketiganya dijalankan tanpa syarat, tanpa berhenti di kegagalan pertama:
     * rollback separuh jalan adalah keadaan yang justru hendak dicegah.
     */
    private suspend fun rollback() {
        journal.start(ProvisioningStep.ROLLBACK)
        runCatching { installer.wipe() }
        runCatching { keys.clearAll() }
        runCatching { state.clear() }
        journal.ok(ProvisioningStep.ROLLBACK)
    }

    private suspend fun rollbackAndFail(code: String, message: String): ProvisioningOutcome {
        rollback()
        return fail(code, message)
    }

    private fun fail(code: String, message: String) =
        ProvisioningOutcome.Failure(code, message, journal.render())

    companion object {
        const val DEVICE_UNKNOWN = "DEVICE_UNKNOWN"
        const val CHALLENGE_EMPTY = "CHALLENGE_EMPTY"
        const val PACKAGE_INVALID = "PACKAGE_INVALID"
        const val KEY_INSTALL_FAILED = "KEY_INSTALL_FAILED"
    }
}