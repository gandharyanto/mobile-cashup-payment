package com.cashup.feature.cardpayment

import com.cashup.cdcp.SaleResponse
import com.cashup.common.network.ApiResult
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionEvent
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.CardType
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardPaymentViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun `physical card is authorized and renders approved result`() = runTest {
        val pinBlock = ByteArray(8) { 0x11 }
        val card = CardTransactionData("4111111111111111D2812101", CardType.CHIP, "9F2601AA", pinBlock)
        var authorizedCard: CardTransactionData? = null
        val reader = object : CardReader {
            override suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener): CardReadResult {
                listener.onEvent(CardTransactionEvent.WaitingForCard)
                listener.onEvent(CardTransactionEvent.CardDetected(CardType.CHIP))
                listener.onEvent(CardTransactionEvent.Authorizing)
                val authorization = listener.authorize(card)
                listener.onEvent(CardTransactionEvent.Completing)
                return CardReadResult.Success(authorization)
            }
            override fun cancel() = Unit
        }
        val dependencies = object : CardPaymentDependencies {
            override suspend fun cardReader() = reader
            override suspend fun authorize(
                card: CardTransactionData,
                amount: BigDecimal,
                tip: BigDecimal,
                idempotencyKey: String,
            ): ApiResult<SaleResponse> {
                authorizedCard = card
                assertEquals(BigDecimal("10000"), amount)
                return ApiResult.Success(SaleResponse("trx-1", "APPROVED", approvalCode = "123456", responseCode = "00"))
            }
        }

        val viewModel = CardPaymentViewModel(dependencies)
        viewModel.start("10000", "0")

        assertEquals(card, authorizedCard)
        assertEquals(PaymentStage.SUCCESS, viewModel.state.value.stage)
        assertEquals("trx-1", viewModel.state.value.result?.transactionId)
        assertTrue(pinBlock.all { it == 0.toByte() })
    }

    @Test fun `invalid amount never opens a card reader`() = runTest {
        var opened = false
        val dependencies = object : CardPaymentDependencies {
            override suspend fun cardReader(): CardReader { opened = true; error("must not be called") }
            override suspend fun authorize(card: CardTransactionData, amount: BigDecimal, tip: BigDecimal,
                                           idempotencyKey: String): ApiResult<SaleResponse> = error("must not be called")
        }

        val viewModel = CardPaymentViewModel(dependencies)
        viewModel.start("0", "")

        assertEquals(PaymentStage.FAILURE, viewModel.state.value.stage)
        assertTrue(!opened)
    }

    @Test fun `suspended device cannot start a transaction`() = runTest {
        var opened = false
        val dependencies = object : CardPaymentDependencies {
            override fun isTransactionAllowed() = false
            override suspend fun cardReader(): CardReader {
                opened = true
                error("must not be called")
            }
            override suspend fun authorize(card: CardTransactionData, amount: BigDecimal, tip: BigDecimal,
                                           idempotencyKey: String): ApiResult<SaleResponse> = error("must not be called")
        }

        val viewModel = CardPaymentViewModel(dependencies)
        viewModel.start("10000", "0")

        assertEquals(PaymentStage.FAILURE, viewModel.state.value.stage)
        assertEquals("Perangkat dinonaktifkan. Transaksi tidak dapat dilakukan.", viewModel.state.value.message)
        assertTrue(!opened)
    }
}
