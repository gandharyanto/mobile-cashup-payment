package com.cashup.qris

import com.cashup.common.network.ApiResult
import java.math.BigDecimal

/** Seam HTTP QRIS: dipakai [QrisPaymentTracker] dan `:app`, dan mudah di-fake di test. */
interface QrisGateway {
    suspend fun payApps(): ApiResult<List<PayApp>>

    /** [attemptId] = satu aksi pengguna; dipakai sebagai `Idempotency-Key`. */
    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>

    /** Selalu memakai `Idempotency-Key` baru. */
    suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult>
}
