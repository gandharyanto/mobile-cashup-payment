package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.KeyBacking
import com.cashup.devicesdk.KeyInstallOutcome
import com.cashup.devicesdk.TerminalKeyInstallResult
import com.cashup.devicesdk.TerminalKeyInstaller
import com.cashup.devicesdk.TerminalKeyMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Slot vault untuk purpose yang tidak kebagian modul vendor. Dimulai dari 10
 * supaya tidak bertabrakan dengan `keyIndex` milik vendor mana pun (PAX 3,
 * Sunmi 2, dan sejenisnya semuanya satu digit).
 */
internal const val VAULT_SLOT_BASE = 10

/**
 * Memasang key DUKPT lewat `KeyManager` milik `edc-sdk`.
 *
 * Modul aman vendor hanya menampung SATU key set DUKPT — `BaseSystemKey`
 * bahkan tidak punya parameter slot. Jadi tepat satu purpose, [vendorSlotPurpose],
 * mendapat perlindungan hardware; sisanya masuk vault ter-enkripsi Keystore.
 * Keputusan siapa yang dapat slot itu ada di satu tempat ini, tidak tersebar.
 *
 * Hasilnya dilaporkan per purpose lewat [KeyInstallOutcome] supaya pemanggil
 * bisa menampilkan dan mencatat mana yang hardware-backed — degradasi ini tidak
 * boleh diam (spec §4.3).
 */
class EdcSdkTerminalKeyInstaller internal constructor(
    private val gateway: KeyManagerGateway,
    private val vendorSlotPurpose: String,
) : TerminalKeyInstaller {

    constructor(context: Context, vendorSlotPurpose: String = "PIN") :
            this(RealKeyManagerGateway(context), vendorSlotPurpose)

    override suspend fun install(
        materials: List<TerminalKeyMaterial>,
    ): TerminalKeyInstallResult = withContext(Dispatchers.IO) {
        val vendorAvailable = gateway.hasVendorModule()
        val outcomes = mutableListOf<KeyInstallOutcome>()

        for (material in materials) {
            val takesVendorSlot = vendorAvailable && material.purpose == vendorSlotPurpose
            val ok = if (takesVendorSlot) {
                gateway.writeToVendorModule(material.ipek, material.ksn)
            } else {
                gateway.writeToVaultSlot(vaultSlot(material.purpose), material.ipek, material.ksn)
            }

            if (!ok) {
                return@withContext TerminalKeyInstallResult.Failed(
                    purpose = material.purpose,
                    reason = if (takesVendorSlot) {
                        "modul aman vendor menolak key"
                    } else {
                        "vault menolak key"
                    },
                )
            }

            outcomes += KeyInstallOutcome(
                purpose = material.purpose,
                backing = if (takesVendorSlot) {
                    KeyBacking.VENDOR_SECURE_MODULE
                } else {
                    KeyBacking.TEE_VAULT_ONLY
                },
            )
        }

        TerminalKeyInstallResult.Installed(outcomes)
    }

    /**
     * Menghapus vault dan cerminannya. **Tidak menyentuh modul aman vendor** —
     * `BaseSystemKey` tidak punya operasi hapus, hanya `writeIPEK`, sehingga
     * IPEK yang sudah masuk hardware tetap di sana. Celah yang diketahui dan
     * disengaja untuk sekarang; lihat KDoc
     * [com.cashup.devicesdk.TerminalKeyInstaller.wipe] dan spec §4.3.
     */
    override suspend fun wipe() = withContext(Dispatchers.IO) {
        gateway.clearAll()
    }
}

internal fun vaultSlot(purpose: String): Int = when (purpose) {
    "TRACK" -> VAULT_SLOT_BASE
    "AMOUNT" -> VAULT_SLOT_BASE + 1
    "PIN" -> VAULT_SLOT_BASE + 2
    "EMV" -> VAULT_SLOT_BASE + 3
    else -> error("Purpose DUKPT tidak dikenal: $purpose")
}
