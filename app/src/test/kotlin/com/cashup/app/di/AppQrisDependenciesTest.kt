package com.cashup.app.di

import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrisGateway
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisPaymentTracker
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppQrisDependenciesTest {
    private class FakeTransport(private val failOnConnect: Boolean = false) : PaymentNotificationTransport {
        val connectedIds = mutableListOf<String>()
        override val isConnected get() = connectedIds.isNotEmpty()
        override fun connect(deviceId: String) {
            if (failOnConnect) throw IllegalStateException("broker unreachable")
            connectedIds += deviceId
        }
        override val messages: Flow<RawNotification> = emptyFlow()
        override fun disconnect() = Unit
    }

    private object NoopGateway : QrisGateway {
        override suspend fun payApps(): ApiResult<List<PayApp>> = ApiResult.Success(emptyList())
        override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> = error("unused")
        override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> = error("unused")
    }

    private fun deps(transport: FakeTransport, deviceId: String? = "dev-1", allowed: Boolean = true) =
        AppQrisDependencies(
            gateway = NoopGateway,
            tracker = QrisPaymentTracker(NoopGateway, PaymentStatusWaiter(transport), transport),
            transport = transport,
            deviceId = { deviceId },
            transactionAllowed = { allowed },
            io = Dispatchers.Unconfined,
        )

    @Test fun `connects the transport with the provisioned device id`() = runBlocking {
        val transport = FakeTransport()
        deps(transport).ensureNotificationsConnected()
        assertEquals(listOf("dev-1"), transport.connectedIds)
    }

    @Test fun `does nothing when the device is not provisioned`() = runBlocking {
        val transport = FakeTransport()
        deps(transport, deviceId = null).ensureNotificationsConnected()
        assertTrue(transport.connectedIds.isEmpty())
    }

    @Test fun `a failing broker connection is swallowed so polling can take over`() = runBlocking {
        deps(FakeTransport(failOnConnect = true)).ensureNotificationsConnected()
    }

    @Test fun `transaction permission is delegated`() {
        assertTrue(deps(FakeTransport(), allowed = true).isTransactionAllowed())
        assertFalse(deps(FakeTransport(), allowed = false).isTransactionAllowed())
    }
}
