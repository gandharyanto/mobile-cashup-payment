package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.devicesdk.TerminalKeyMaterial
import com.lib.core.DeviceManager
import com.lib.core.DuktpVaultCompat
import com.lib.core.KeyManager

/** Reads the vault mirror written by [EdcSdkTerminalKeyInstaller]. */
class EdcSdkDukptKeyProvider(context: Context) : DukptKeyProvider {
    private val appContext = context.applicationContext
    private val vault = DuktpVaultCompat
    private val keyManager by lazy { KeyManager.getInstance(appContext) }

    /** Indeks slot modul aman vendor, atau null kalau tidak ada modul vendor yang aktif. */
    private fun vendorKeyIndex(): Int? = if (DeviceManager.systemKey != null) keyManager.keyIndex else null

    override fun load(purpose: String): TerminalKeyMaterial? {
        for (slot in dukptSlotCandidates(purpose, vendorKeyIndex())) {
            val material = loadFromSlot(purpose, slot)
            if (material != null) return material
        }
        return null
    }

    private fun loadFromSlot(purpose: String, slot: Int): TerminalKeyMaterial? {
        val ipek = vault.loadIpekForSet(appContext, slot) ?: return null
        val ksn = vault.loadKsnForSet(appContext, slot)
        if (ksn == null) {
            ipek.fill(0)
            return null
        }
        return TerminalKeyMaterial(purpose, ipek, ksn)
    }

    @Synchronized
    override fun nextCounter(): Int {
        val slot = vaultSlot("TRACK")
        val next = vault.getCounterForSet(appContext, slot) + 1
        require(next in 1..0x1FFFFF) { "Penghitung DUKPT habis" }
        vault.setCounterForSet(appContext, slot, next)
        return next.toInt()
    }
}

/**
 * Slot yang dicoba untuk [purpose], berurutan. Hanya PIN yang bisa ada di modul vendor; selebihnya selalu vault.
 *
 * Modul vendor aktif (`DeviceManager.systemKey != null`) tidak berarti IPEK PIN dipasang ke sana: reader mPOS yang
 * tersambung setelah provisioning (ANFU) ikut mengisi `systemKey`, sedangkan IPEK PIN hasil provisioning di HP ada di
 * vault. Karena itu vault selalu menjadi cadangan.
 */
internal fun dukptSlotCandidates(purpose: String, vendorKeyIndex: Int?): List<Int> =
    if (purpose == "PIN" && vendorKeyIndex != null) listOf(vendorKeyIndex, vaultSlot(purpose)).distinct()
    else listOf(vaultSlot(purpose))

