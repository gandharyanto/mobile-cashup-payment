package com.cashup.devicesdk.mpos

import android.content.Context
import com.lib.core.DeviceManager
import com.lib.core.helpers.BaseLedHelper

/**
 * LED yang tidak melakukan apa-apa. `startPinInput` vendor ANFU menyalakan LED lewat
 * `DeviceManager.led` (lateinit) tanpa memeriksanya; di HP/tablet biasa tidak ada modul vendor
 * yang mengisinya, sehingga PIN pertama langsung mematikan aplikasi.
 */
internal class NoOpLedHelper : BaseLedHelper() {
    override fun connect(context: Context): BaseLedHelper = this
    override fun start() = Unit
    override suspend fun startThread() = Unit
    override fun stopLed() = Unit
}

/** Mengisi `DeviceManager.led` hanya kalau belum ada; LED asli milik modul vendor tidak ditimpa. */
internal fun ensureLedHelper() {
    try {
        DeviceManager.led
    } catch (_: UninitializedPropertyAccessException) {
        DeviceManager.led = NoOpLedHelper()
    }
}
