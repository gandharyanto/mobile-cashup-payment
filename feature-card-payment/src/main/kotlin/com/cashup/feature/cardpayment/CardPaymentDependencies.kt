package com.cashup.feature.cardpayment

import com.cashup.cdcp.SaleResponse
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardReader
import java.math.BigDecimal

interface CardPaymentDependencies {
    suspend fun cardReader(): CardReader
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
