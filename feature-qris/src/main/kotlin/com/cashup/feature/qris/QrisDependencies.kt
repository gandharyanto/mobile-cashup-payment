package com.cashup.feature.qris

import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisPaymentResult
import java.math.BigDecimal

interface QrisDependencies {
    /** Dibaca ulang di batas transaksi supaya suspend jarak jauh langsung berlaku. */
    fun isTransactionAllowed(): Boolean = true

    /** Idempotent; kegagalan ditelan (polling menjadi jalur utama). */
    suspend fun ensureNotificationsConnected()

    suspend fun payApps(): ApiResult<List<PayApp>>

    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>

    /** Suspend sampai final atau kedaluwarsa; tidak melempar selain CancellationException. */
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult
}

interface QrisDependenciesOwner {
    val qrisDependencies: QrisDependencies
}
