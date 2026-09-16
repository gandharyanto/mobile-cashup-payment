package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Scanner
import com.lib.core.SDKManager
import com.lib.core.interfaces.QRCodeListener
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Scanner QR hardware vendor lewat `SDKManager`.
 *
 * `BaseQRCodeCamera.start` meminta `Activity`, bukan `Context`, jadi activity
 * yang sedang aktif diambil dari `SDKManager.currentActivity` — SDK melacaknya
 * sendiri lewat `ActivityLifecycleCallbacks`.
 *
 * Mengembalikan `null` kalau device tidak punya scanner, atau kalau tidak ada
 * hasil sampai [timeoutMillis] habis. Pemanggil yang memutuskan apa artinya;
 * lihat `QrScanSource`.
 *
 * Kamera ditutup di setiap jalur keluar, termasuk saat coroutine dibatalkan —
 * scanner vendor yang dibiarkan terbuka menahan kameranya dan percobaan
 * berikutnya gagal tanpa pesan yang jelas.
 */
class EdcSdkScanner(context: Context) : Scanner {

    private val appContext = context.applicationContext

    override suspend fun scanQr(timeoutMillis: Long): String? {
        val camera = SDKManager.qrCodeCamera ?: return null
        val activity = SDKManager.getCurrentActivity() ?: return null

        return try {
            withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    camera.init(appContext)
                    camera.qrCodeListener = object : QRCodeListener {
                        override fun onResult(value: String?) {
                            if (continuation.isActive) continuation.resume(value)
                        }
                    }
                    continuation.invokeOnCancellation { runCatching { camera.close() } }
                    camera.start(activity)
                }
            }
        } catch (e: TimeoutCancellationException) {
            null
        } finally {
            runCatching { camera.close() }
        }
    }
}
