package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.SerialNumberProvider
import com.lib.core.SDKManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Nomor seri hardware lewat `SDKManager` milik `edc-sdk`.
 *
 * `SDKManager.autoDetectDevice` mencocokkan `Build.BRAND` ke module vendor yang
 * terpasang lalu melapor lewat callback — itulah kenapa `DeviceSdkRegistry`
 * (pencocokan prefix `Build.MODEL`) dihapus di Task 2; pekerjaan itu sudah
 * dilakukan di sini, dengan sinyal yang lebih baik.
 *
 * Mengembalikan `null` kalau tidak ada module vendor yang cocok dengan hardware
 * ini. Provisioning tidak boleh dilanjutkan dalam keadaan itu: nomor seri adalah
 * identitas device di backend (spec §3.3).
 *
 * Tidak ada tes unit untuk kelas ini — `autoDetectDevice` menyentuh binder
 * Android dan hardware vendor sungguhan. Divalidasi manual di terminal fisik.
 */
class EdcSdkSerialNumberProvider(context: Context) : SerialNumberProvider {

    private val appContext = context.applicationContext

    override suspend fun serialNumber(): String? = withContext(Dispatchers.IO) {
        val connected = suspendCancellableCoroutine { continuation ->
            SDKManager.autoDetectDevice(appContext) { ok ->
                if (continuation.isActive) continuation.resume(ok)
            }
        }
        if (!connected) return@withContext null
        runCatching { SDKManager.requireHelper().serialNumber }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}