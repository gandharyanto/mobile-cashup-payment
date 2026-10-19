package com.cashup.feature.cardpayment

import com.cashup.cdcp.SaleResponse
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardReader
import java.math.BigDecimal

interface CardPaymentDependencies {
    /** Re-read at transaction boundaries so a remote device suspension takes effect immediately. */
    fun isTransactionAllowed(): Boolean = true

    suspend fun cardReader(): CardReader

    /**
     * Menyiapkan reader eksternal (connect, key, AID/CAPK) di latar belakang sebelum Bayar. Aman dipanggil berulang
     * dan tidak melakukan apa-apa untuk terminal EDC bawaan.
     */
    fun prepareReader() = Unit

    suspend fun authorize(
        card: CardTransactionData,
        amount: BigDecimal,
        tip: BigDecimal,
        idempotencyKey: String,
    ): ApiResult<SaleResponse>
}

interface CardPaymentDependenciesOwner {
    val cardPaymentDependencies: CardPaymentDependencies
}
