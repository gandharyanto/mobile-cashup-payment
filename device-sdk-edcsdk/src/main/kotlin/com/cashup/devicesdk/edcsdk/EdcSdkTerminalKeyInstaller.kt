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
        var nextVaultSlot = VAULT_SLOT_BASE

        for (material in materials) {
            val takesVendorSlot = vendorAvailable && material.purpose == vendorSlotPurpose
            val ok = if (takesVendorSlot) {
                gateway.writeToVendorModule(material.ipek, material.ksn)
            } else {
                gateway.writeToVaultSlot(nextVaultSlot++, material.ipek, material.ksn)
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

    override suspend fun wipe() = withContext(Dispatchers.IO) {
        gateway.clearAll()
    }
}