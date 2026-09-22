package com.cashup.devicesdk.factory

import android.content.Context
import android.util.Log
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.edcsdk.EdcSdkConnector
import com.cashup.devicesdk.mpos.MposConnector

class NoDeviceSdkAvailableException : Exception("Tidak ada device SDK yang tersedia di device ini")

/**
 * Satu-satunya titik fan-out (spec §5). Urutan berjenjang, bukan race murni:
 * EDC built-in dicoba dulu (deteksi cepat & deterministik), mPOS baru dicoba
 * kalau tidak ada -- lihat spec §5 untuk kenapa "connect" mPOS bukan berarti
 * reader fisik sudah terpasang.
 */
class DeviceSdkFactory internal constructor(
    private val candidates: List<suspend () -> DeviceSdk?>,
    private val reportFailure: (Int, Exception) -> Unit = { _, _ -> },
) {
    constructor(context: Context) : this(
        listOf(
            { EdcSdkConnector.tryConnect(context) },
            { MposConnector.tryConnect(context) },
        ),
        { index, failure ->
            Log.w(TAG, "Device SDK candidate #$index tidak tersedia: ${failure.message}", failure)
        },
    )

    suspend fun connect(): DeviceSdk {
        for ((index, candidate) in candidates.withIndex()) {
            // Exception = "vendor ini bilang tidak", lanjut ke kandidat
            // berikutnya. Error (mis. AAR salah dikonfigurasi) sengaja TIDAK
            // ditangkap di sini -- harus meledakkan build/CI, bukan diam-diam
            // dianggap "vendor tidak ada" (spec §6). CancellationException
            // dilempar ulang: itu bukan "vendor bilang tidak", itu coroutine
            // scope pemanggil yang dibatalkan -- menelannya di sini akan
            // melanggar cooperative cancellation.
            val result = try {
                candidate()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (unavailable: Exception) {
                reportFailure(index, unavailable)
                null
            }
            if (result != null) return result
        }
        throw NoDeviceSdkAvailableException()
    }

    private companion object {
        const val TAG = "DeviceSdkFactory"
    }
}
