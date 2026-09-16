package com.cashup.provisioning.domain

import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.audit.Evidence
import com.cashup.provisioning.audit.ProvisioningJournal
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
    private val deviceIdentity: DeviceIdentitySink = DeviceIdentitySink { },
    private val unwrapperFactory: (RsaUnwrapper) -> PackageUnwrapper = { PackageUnwrapper(it) },
    private val journal: ProvisioningJournal = ProvisioningJournal(),
) {

    /**
     * [onStep] dipanggil saat setiap langkah DIMULAI, di coroutine pemanggil.
     * Ia ada supaya layar Processing bisa menunjukkan langkah mana yang sedang
     * berjalan alih-alih spinner tanpa keterangan — ceremony ini menunggu dua
     * HSM di `DOWNLOAD_PACKAGE` dan bisa terasa menggantung tanpa itu.
     */
    suspend operator fun invoke(
        rawChallengeCode: String,
        onStep: (ProvisioningStep) -> Unit = {},
    ): ProvisioningOutcome {
        journal.clear()

        var currentStep = ProvisioningStep.DETECT_DEVICE
        try {
            return invokeSteps(rawChallengeCode) { currentStep = it; onStep(it) }
        } catch (e: Throwable) {
            // Setiap kegagalan yang tak terduga -- MGF1 yang tidak cocok saat
            // Cipher.doFinal, exception dari Android Keystore, IPEK yang
            // malformed di keyCheckValue, binder vendor yang melempar
            // RuntimeException, state.save yang gagal, dan lain-lain -- harus
            // tetap melewati rollback dan tidak pernah lolos dari invoke()
            // begitu saja (spec §4.5, §7.1).
            //
            // Throwable, BUKAN Exception: NoClassDefFoundError sudah terbukti
            // sebagai mode kegagalan nyata di codebase ini (class API di atas
            // 23 yang lolos ke runtime lewat module JVM murni), dan terminal
            // 1 GB bisa melempar OutOfMemoryError. Sebuah Error yang lolos dari
            // sini meninggalkan device memegang key yang backend tidak tahu --
            // persis keadaan yang seluruh kelas ini ada untuk mencegahnya.
            journal.failed(currentStep, UNEXPECTED_ERROR, mapOf("message" to (e.message ?: "-")))
            rollback()
            return fail(UNEXPECTED_ERROR, e.message ?: "Kesalahan tak terduga: ${e::class.simpleName}")
        } finally {
            // Tanpa syarat, di SEMUA jalur keluar. Pada jalur sukses nomor seri
            // sudah tersimpan sehingga `deviceId()` jatuh ke state; pada jalur
            // gagal device belum terprovisioning, jadi identitas harus kembali
            // kosong -- termasuk pada jalur gagal awal yang tidak memanggil
            // rollback (DEVICE_UNKNOWN, CHALLENGE_EMPTY, REDEEM ditolak).
            deviceIdentity.setInFlightSerial(null)
        }
    }

    private suspend fun invokeSteps(
        rawChallengeCode: String,
        onStep: (ProvisioningStep) -> Unit,
    ): ProvisioningOutcome {
        // 1. Nomor seri. Tanpa ini backend tidak punya identitas untuk device
        //    ini, jadi jaringan tidak perlu disentuh sama sekali.
        onStep(ProvisioningStep.DETECT_DEVICE)
        journal.start(ProvisioningStep.DETECT_DEVICE)
        val serialNumber = serialNumbers.serialNumber()
        if (serialNumber.isNullOrBlank()) {
            journal.failed(ProvisioningStep.DETECT_DEVICE, DEVICE_UNKNOWN)
            return fail(DEVICE_UNKNOWN, "Perangkat tidak dikenali SDK vendor mana pun")
        }
        // `X-Device-Id` = nomor seri (spec §3.3), dan ia sudah diketahui DI SINI
        // -- jauh sebelum `state.save` di langkah terakhir. Diumumkan sekarang
        // supaya `/package` dan `/activate` punya identitas untuk ditandatangani;
        // tanpa ini ceremony tidak pernah bisa lewat DOWNLOAD_PACKAGE.
        deviceIdentity.setInFlightSerial(serialNumber)
        journal.ok(ProvisioningStep.DETECT_DEVICE, mapOf("serialNumber" to serialNumber))

        // 2. Kode QR. Dinormalisasi sama seperti di sisi server, supaya input
        //    manual bertanda hubung atau berspasi tetap cocok dengan hasil scan.
        //
        //    Divalidasi SEBELUM keypair dibuat, bukan sesudah: kode kosong
        //    adalah satu-satunya jalur gagal yang tidak melewati rollback, dan
        //    urutan lama membuatnya keluar setelah key material sudah ada di
        //    Keystore. Memvalidasi lebih dulu menghapus anomali itu sepenuhnya
        //    alih-alih menanganinya sebagai kasus khusus.
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

        // 3. Keypair. Idempoten: percobaan ulang setelah gagal memakai key yang
        //    sama, sehingga public key yang didaftarkan tetap konsisten.
        onStep(ProvisioningStep.GENERATE_KEYS)
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

        // 4. Redeem. Satu-satunya panggilan yang tidak ditandatangani.
        onStep(ProvisioningStep.REDEEM)
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
        onStep(ProvisioningStep.DOWNLOAD_PACKAGE)
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
        onStep(ProvisioningStep.UNWRAP_PACKAGE)
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
        onStep(ProvisioningStep.INSTALL_KEYS)
        journal.start(ProvisioningStep.INSTALL_KEYS)
        val installResult = installer.install(materials)
        // Zeroisasi tanpa syarat begitu vendor/vault selesai memproses --
        // sukses atau gagal, plaintext IPEK/KSN tidak boleh tetap hidup di
        // memori. KCV yang dibutuhkan untuk `activate` sudah dihitung di atas
        // dari salinan, jadi zeroisasi di sini tidak memengaruhi nilai itu.
        materials.forEach { it.zeroize() }
        val installed: List<KeyInstallOutcome> = when (installResult) {
            is TerminalKeyInstallResult.Installed -> installResult.outcomes
            is TerminalKeyInstallResult.Failed -> {
                journal.failed(
                    ProvisioningStep.INSTALL_KEYS,
                    KEY_INSTALL_FAILED,
                    mapOf("purpose" to installResult.purpose),
                )
                return rollbackAndFail(
                    KEY_INSTALL_FAILED,
                    "Gagal memasang key untuk purpose ${installResult.purpose}: ${installResult.reason}",
                )
            }
        }
        journal.ok(
            ProvisioningStep.INSTALL_KEYS,
            installed.associate { it.purpose to it.backing.name },
        )

        // 9. Activate. Mengirim KCV sebagai bukti ke backend.
        onStep(ProvisioningStep.ACTIVATE)
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
        onStep(ProvisioningStep.PERSIST_STATE)
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
        // Setelah rollback device TIDAK terprovisioning, jadi identitas
        // in-flight ikut dicabut: `deviceId()` harus kembali null sampai
        // percobaan berikutnya mendeteksi ulang nomor serinya.
        runCatching { deviceIdentity.setInFlightSerial(null) }
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
        const val UNEXPECTED_ERROR = "UNEXPECTED_ERROR"
    }
}