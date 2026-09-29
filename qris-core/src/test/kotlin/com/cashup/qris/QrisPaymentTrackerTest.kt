package com.cashup.qris

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import java.math.BigDecimal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeTransport(connected: Boolean) : PaymentNotificationTransport {
    override var isConnected: Boolean = connected
    var disconnectCalls = 0
    private val flow = MutableSharedFlow<RawNotification>(extraBufferCapacity = 16)
    override fun connect(deviceId: String) { isConnected = true }
    override val messages: Flow<RawNotification> = flow
    override fun disconnect() { disconnectCalls++ }
    fun emit(notification: RawNotification) { flow.tryEmit(notification) }
}

private class FakeGateway(private val now: () -> Long) : QrisGateway {
    val inquiryAt = mutableListOf<Long>()
    var script: (Int) -> ApiResult<QrisPaymentResult> = { initiated() }
    override suspend fun payApps(): ApiResult<List<PayApp>> = error("unused")
    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> = error("unused")
    override suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult> {
        inquiryAt += now()
        return script(inquiryAt.size)
    }
}

private fun result(status: QrisStatus) = ApiResult.Success(QrisPaymentResult("tx-1", status, BigDecimal("15000")))
private fun initiated() = result(QrisStatus.INITIATED)
private fun paid() = result(QrisStatus.AUTHORIZED)
private fun qr() = GeneratedQr("tx-1", "000001", BigDecimal("15000"), 2, "DANA", "Toko", "0002", "APP-a")

private fun push(
    tx: String, type: String = "qris.payment_paid", v: Int = 1, module: String = "qris", detail: String? = null,
) = RawNotification(
    "cashup/staging/v1/devices/d",
    """{"v":$v,"id":"m-1","type":"$type","module":"$module","data":{"amount":"15000.00","currency":"IDR",""" +
        """"reference":"1","invoiceNumber":"000001","transactionId":"$tx"${detail?.let { ",\"detail\":$it" } ?: ""}}}""",
)

@OptIn(ExperimentalCoroutinesApi::class)
class QrisPaymentTrackerTest {
    private fun TestScope.tracker(transport: FakeTransport, gateway: FakeGateway) =
        QrisPaymentTracker(gateway, PaymentStatusWaiter(transport), transport)

    @Test fun `matching push triggers exactly one confirming inquiry and carries push detail`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1", detail = """{"mid":"M1","rrn":"R1","issuerName":null}"""))
        val outcome = deferred.await()
        assertEquals(QrisStatus.AUTHORIZED, outcome.status)
        assertEquals("M1", outcome.pushDetail?.mid)
        assertNull(outcome.pushDetail?.issuerName)
        assertEquals(1, gateway.inquiryAt.size)
        assertEquals("tracker must never disconnect the transport", 0, transport.disconnectCalls)
    }

    @Test fun `push without detail key still confirms`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1"))
        val outcome = deferred.await()
        assertTrue(outcome.status.isPaid)
        assertNull(outcome.pushDetail)
    }

    @Test fun `push while inquiry still INITIATED keeps polling every 3s until final`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 2) initiated() else paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1", detail = """{"mid":"M1"}"""))
        val outcome = deferred.await()
        assertTrue(outcome.status.isPaid)
        assertEquals("M1", outcome.pushDetail?.mid)
        assertEquals(listOf(0L, 3_000L), gateway.inquiryAt)
    }

    @Test fun `unrelated or malformed pushes are ignored and do not trigger inquiry`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("other-tx"))
        transport.emit(push("tx-1", type = "qris.something_else"))
        transport.emit(push("tx-1", v = 2))
        transport.emit(push("tx-1", module = "cdcp"))
        transport.emit(RawNotification("t", "not json at all"))
        transport.emit(RawNotification("t", """{"v":1,"type":"qris.payment_paid","module":"qris"}"""))
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue("no inquiry before the 5s push window ends", gateway.inquiryAt.isEmpty())
        assertFalse(deferred.isCompleted)
        transport.emit(push("tx-1"))
        assertTrue(deferred.await().status.isPaid)
    }

    @Test fun `same push delivered twice results in a single inquiry`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { paid() } }
        val deferred = async { tracker(transport, gateway).track(qr(), 600) }
        runCurrent()
        transport.emit(push("tx-1"))
        transport.emit(push("tx-1"))
        deferred.await()
        runCurrent()
        assertEquals(1, gateway.inquiryAt.size)
    }

    @Test fun `without push polling starts at 5s and repeats every 10s while mqtt is connected`() = runTest {
        val transport = FakeTransport(connected = true)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 3) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 15_000L, 25_000L), gateway.inquiryAt)
    }

    @Test fun `without push polling repeats every 3s when mqtt is not connected`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 3) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 8_000L, 11_000L), gateway.inquiryAt)
    }

    @Test fun `failed inquiry during polling is ignored and retried`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply {
            script = { n -> if (n == 1) ApiResult.Failure(ApiError("NETWORK", "down")) else paid() }
        }
        val outcome = tracker(transport, gateway).track(qr(), 600)
        assertTrue(outcome.status.isPaid)
        assertEquals(listOf(5_000L, 8_000L), gateway.inquiryAt)
    }

    @Test fun `past the deadline one last inquiry decides then falls back to EXPIRED`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }
        val outcome = tracker(transport, gateway).track(qr(), 10) // deadline = 10s + 5s grace = 15s
        assertEquals(QrisStatus.EXPIRED, outcome.status)
        assertEquals("tx-1", outcome.transactionId)
        assertEquals(BigDecimal("15000"), outcome.amount)
        assertEquals(listOf(5_000L, 8_000L, 11_000L, 14_000L, 15_000L), gateway.inquiryAt)
    }

    @Test fun `last inquiry at the deadline can still report a payment`() = runTest {
        val transport = FakeTransport(connected = false)
        val gateway = FakeGateway { testScheduler.currentTime }.apply { script = { n -> if (n < 5) initiated() else paid() } }
        val outcome = tracker(transport, gateway).track(qr(), 10)
        assertEquals(QrisStatus.AUTHORIZED, outcome.status)
        assertEquals(15_000L, gateway.inquiryAt.last())
    }
}
