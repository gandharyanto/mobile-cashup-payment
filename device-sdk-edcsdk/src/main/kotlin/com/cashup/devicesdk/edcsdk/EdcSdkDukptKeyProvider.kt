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

    private fun slot(purpose: String): Int =
        if (purpose == "PIN" && DeviceManager.systemKey != null) keyManager.keyIndex else vaultSlot(purpose)

    override fun load(purpose: String): TerminalKeyMaterial? {
        val slot = slot(purpose)
        val ipek = vault.loadIpekForSet(appContext, slot) ?: return null
        val ksn = vault.loadKsnForSet(appContext, slot) ?: run { ipek.fill(0); return null }
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
