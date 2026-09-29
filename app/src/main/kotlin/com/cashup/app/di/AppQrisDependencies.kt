package com.cashup.app.di

import android.util.Log
import com.cashup.common.network.ApiResult
import com.cashup.feature.qris.QrisDependencies
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisGateway
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisPaymentTracker
import java.math.BigDecimal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Jembatan `:feature-qris` -> `:qris-core`/`:notification-core`. Koneksi MQTT dibuat lazily saat
 * layar QRIS pertama dibuka (bukan saat startup app), lalu tetap hidup selama proses; tidak pernah
 * `disconnect()` dari alur pembayaran (spec 2026-09-29 §6).
 */
class AppQrisDependencies(
    private val gateway: QrisGateway,
    private val tracker: QrisPaymentTracker,
    private val transport: PaymentNotificationTransport,
    private val deviceId: () -> String?,
    private val transactionAllowed: () -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : QrisDependencies {

    override fun isTransactionAllowed(): Boolean = transactionAllowed()

    override suspend fun ensureNotificationsConnected() {
        val id = deviceId() ?: return
        // Serialisasi: connect Paho memblokir; dua client dengan id sama akan saling menendang di broker.
        connectLock.withLock {
            if (transport.isConnected) return
            withContext(io) {
                try {
                    transport.connect(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Push hanyalah percepatan; tanpa MQTT tracker tetap jalan lewat polling 3 dtk.
                    Log.w(TAG, "MQTT connect gagal, memakai polling: ${e.message}")
                }
            }
        }
    }

    private val connectLock = Mutex()

    override suspend fun payApps(): ApiResult<List<PayApp>> = gateway.payApps()

    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> =
        gateway.generate(qrisHostId, amount, attemptId)

    override suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult =
        tracker.track(qr, expirySeconds)

    private companion object {
        const val TAG = "QrisDependencies"
    }
}
