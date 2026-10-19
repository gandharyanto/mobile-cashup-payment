package com.cashup.reader

import android.content.Context
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.PairableCardReader
import com.cashup.devicesdk.PairedDeviceInfo
import com.cashup.devicesdk.factory.DeviceSdkFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Satu pintu ke card reader untuk terminal EDC bawaan (T6D) dan reader mPOS (ANFU). Pemanggil tidak perlu tahu
 * mana yang terpasang.
 *
 * - [cardReader] mengembalikan reader yang dipakai transaksi. Reader mPOS dipakai ulang antar pemanggilan: connect,
 *   key, dan AID/CAPK-nya makan sekitar satu menit. Terminal EDC bawaan tetap dibuat baru setiap kali.
 * - [prepareInBackground] menyiapkan reader mPOS yang diingat (connect + key + AID/CAPK) di [scope], supaya Bayar
 *   tidak menunggu. Untuk terminal EDC bawaan atau device tanpa reader tersimpan tidak ada yang disiapkan, dan
 *   hasilnya langsung `true`. Kegagalan koneksi (reader mati, di luar jangkauan) dilaporkan sebagai `false`, bukan
 *   exception, supaya pemanggil tetap lanjut dan Bayar mencoba lagi.
 */
class ReaderSession(
    private val connectReader: suspend () -> CardReader,
    private val scope: CoroutineScope,
) {
    private val readerLock = Mutex()
    private var externalReader: CardReader? = null

    // Terminal EDC bawaan tidak pernah punya reader eksternal: cukup diperiksa sekali, supaya pemanggilan berkala
    // tidak menyentuh SDK EDC berulang kali. Kegagalan probe sengaja tidak diingat.
    @Volatile private var noExternalReader = false

    private var preparing: Deferred<Boolean>? = null

    /** Reader untuk transaksi. */
    suspend fun cardReader(): CardReader = readerLock.withLock {
        externalReader ?: connectReader().also { reader ->
            if (reader is PairableCardReader) externalReader = reader
        }
    }

    /** Reader eksternal yang diingat (tanpa scan dan tanpa izin Bluetooth), atau null kalau tidak ada. */
    suspend fun rememberedReader(): PairedDeviceInfo? = pairableReader()?.rememberedDevice()

    /**
     * Menyiapkan reader yang diingat sekarang dan menunggu hasilnya.
     *
     * @return `true` kalau reader siap atau memang tidak ada yang perlu disiapkan.
     */
    suspend fun prepareNow(): Boolean {
        val reader = pairableReader() ?: return true
        val device = reader.rememberedDevice() ?: return true
        return try {
            reader.selectDevice(device.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            false
        }
    }

    /**
     * Seperti [prepareNow], tapi berjalan di scope sesi, jadi rotasi layar atau layar yang ditinggalkan tidak
     * membatalkannya. Dipanggil lagi saat masih berjalan, mengembalikan pekerjaan yang sama.
     */
    @Synchronized
    fun prepareInBackground(): Deferred<Boolean> = preparing?.takeIf { it.isActive }
        ?: scope.async { prepareNow() }.also { preparing = it }

    private suspend fun pairableReader(): PairableCardReader? {
        if (noExternalReader) return null
        return try {
            val pairable = cardReader() as? PairableCardReader
            if (pairable == null) noExternalReader = true
            pairable
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            null
        }
    }

    companion object {
        /** Sesi untuk aplikasi: reader dipilih otomatis (EDC bawaan dulu, lalu mPOS) lewat [DeviceSdkFactory]. */
        fun create(
            context: Context,
            scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
        ): ReaderSession {
            val factory = DeviceSdkFactory(context.applicationContext)
            return ReaderSession(
                connectReader = {
                    requireNotNull(factory.connect().cardReader) { "SDK device tidak menyediakan card reader" }
                },
                scope = scope,
            )
        }
    }
}
