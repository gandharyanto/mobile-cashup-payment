package com.cashup.app.di

import com.cashup.kernel.CashupError
import com.cashup.kernel.CashupResult
import com.cashup.kernel.Money
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import com.cashup.qris.data.GsonPushParser
import com.cashup.qris.data.NotificationQrisSignalSource
import com.cashup.qris.domain.GeneratedQr
import com.cashup.qris.domain.PayApp
import com.cashup.qris.domain.QrisCancelResult
import com.cashup.qris.domain.QrisGateway
import com.cashup.qris.domain.QrisHistoryPage
import com.cashup.qris.domain.QrisPaymentResult
import com.cashup.qris.domain.QrisPaymentTracker
import com.cashup.qris.domain.QrisRefundResult
import com.cashup.qris.domain.QrisStatus
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** QA-02: cancel/refund/history reach the gateway with the caller's attemptId and cursor, untouched. */
class AppQrisDependenciesActionsTest {
    private class Gateway : QrisGateway {
        val cancels = mutableListOf<Pair<String, String>>()
        val refunds = mutableListOf<Pair<String, String>>()
        val histories = mutableListOf<Pair<String?, Int>>()
        val failure = CashupResult.Failure(CashupError.Network("NET", "down"))
        override suspend fun payApps(): CashupResult<List<PayApp>> = error("unused")
        override suspend fun generate(qrisHostId: Int, amount: Money, attemptId: String): CashupResult<GeneratedQr> = error("unused")
        override suspend fun inquiry(transactionId: String): CashupResult<QrisPaymentResult> = error("unused")
        override suspend fun cancel(transactionId: String, attemptId: String, reason: String?): CashupResult<QrisCancelResult> {
            cancels += transactionId to attemptId
            return CashupResult.Success(QrisCancelResult(transactionId, QrisStatus.CANCELLED, BigDecimal.ONE))
        }
        override suspend fun refund(transactionId: String, attemptId: String, reason: String?): CashupResult<QrisRefundResult> {
            refunds += transactionId to attemptId
            return failure
        }
        override suspend fun history(cursor: String?, limit: Int, statuses: List<QrisStatus>): CashupResult<QrisHistoryPage> {
            histories += cursor to limit
            return CashupResult.Success(QrisHistoryPage(emptyList(), "next"))
        }
    }

    private class Transport : PaymentNotificationTransport {
        override val isConnected = false
        override fun connect(deviceId: String) = Unit
        override val messages: Flow<RawNotification> = emptyFlow()
        override fun disconnect() = Unit
    }

    private fun deps(gateway: QrisGateway): AppQrisDependencies {
        val transport = Transport()
        return AppQrisDependencies(
            gateway = gateway,
            tracker = QrisPaymentTracker(gateway, NotificationQrisSignalSource(PaymentStatusWaiter(transport), transport), GsonPushParser()),
            transport = transport,
            deviceId = { "dev-1" },
            transactionAllowed = { true },
            io = Dispatchers.Unconfined,
        )
    }

    @Test fun `cancel forwards transaction id and attempt id unchanged`() = runBlocking {
        val gw = Gateway()
        val result = deps(gw).cancel("tx-1", "attempt-1")
        assertEquals(listOf("tx-1" to "attempt-1"), gw.cancels)
        assertEquals(QrisStatus.CANCELLED, (result as CashupResult.Success).value.status)
    }

    @Test fun `refund forwards the attempt id and passes a failure through unchanged`() = runBlocking {
        val gw = Gateway()
        val result = deps(gw).refund("tx-1", "attempt-2")
        assertEquals(listOf("tx-1" to "attempt-2"), gw.refunds)
        assertSame(gw.failure, result)
    }

    @Test fun `history forwards cursor and limit`() = runBlocking {
        val gw = Gateway()
        val page = (deps(gw).history("c1", 20) as CashupResult.Success).value
        assertEquals(listOf<Pair<String?, Int>>("c1" to 20), gw.histories)
        assertEquals("next", page.nextCursor)
    }
}
