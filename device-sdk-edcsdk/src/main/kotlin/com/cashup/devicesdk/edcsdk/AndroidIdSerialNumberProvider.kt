package com.cashup.devicesdk.edcsdk

import android.content.Context
import android.provider.Settings
import com.cashup.devicesdk.SerialNumberProvider

/**
 * Identitas untuk device mobile (non-EDC) lewat `Settings.Secure.ANDROID_ID`.
 *
 * Tanpa permission dan stabil per HP + signing key app, tapi berubah setelah
 * factory reset (HP harus di-provision ulang). `Build.getSerial()` tidak dipakai
 * karena butuh permission privileged di Android 10+.
 *
 * Tidak ada tes unit -- membaca `Settings.Secure` dari framework Android.
 */
class AndroidIdSerialNumberProvider(context: Context) : SerialNumberProvider {

    private val appContext = context.applicationContext

    override suspend fun serialNumber(): String? =
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() && it != BROKEN_EMULATOR_ID }

    private companion object {
        /** ANDROID_ID bawaan bug emulator/HP lama -- sama di banyak device. */
        const val BROKEN_EMULATOR_ID = "9774d56d682e549c"
    }
}
