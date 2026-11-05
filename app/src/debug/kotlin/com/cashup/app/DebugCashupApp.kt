package com.cashup.app

import android.util.Log
import com.cashup.devicesdk.factory.DeviceSdkFactory

/**
 * Debug-only Application: installs the Newland key provider from the local, git-ignored
 * config.properties (via the generated [DebugKeys]). Without values the provider stays
 * uninstalled and key injection fails closed. Never present in release or minifyCheck.
 */
class DebugCashupApp : CashupApp() {
    override fun onCreate() {
        super.onCreate()
        val kek = DebugKeys.newlandKekHex?.let(::hexToBytes)
        val dataKey = DebugKeys.newlandDataEncryptKeyHex?.let(::hexToBytes)
        if (kek == null || dataKey == null) {
            Log.w(TAG, "Newland key provider not installed: no local config.properties values")
            return
        }
        try {
            DeviceSdkFactory.installNewlandMposKeys(kek, dataKey)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Newland key provider not installed: invalid key length")
        }
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private companion object {
        const val TAG = "DebugCashupApp"
    }
}
