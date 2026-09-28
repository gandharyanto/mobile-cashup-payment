package com.cashup.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

private class FakeTransport : PaymentNotificationTransport {
    override var isConnected: Boolean = false
        private set
    val disconnectCallCount get() = _disconnectCallCount
    private var _disconnectCallCount = 0
    private val flow = MutableSharedFlow<RawNotification>(extraBufferCapacity = 16)

    override fun connect(deviceId: String) { isConnected = true }
    override val messages: Flow<RawNotification> = flow
    override fun disconnect() { _disconnectCallCount++; isConnected = false }

    suspend fun emit(notification: RawNotification) = flow.emit(notification)
}

class PaymentStatusWaiterTest {

    @Test
    fun `mqtt message matching before timeout wins, statusCheck never called`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { statusCheckCalls++; null },
            )
            assertEquals("paid", result)
        }

        advanceTimeBy(1_000L)
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()

        assertEquals(0, statusCheckCalls)
        assertEquals(0, transport.disconnectCallCount)
    }

    @Test
    fun `no mqtt message, statusCheck succeeds on first poll at exactly timeoutMs`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = { statusCheckCalls++; "final-status" },
        )

        assertEquals("final-status", result)
        assertEquals(1, statusCheckCalls)
        assertEquals(0, transport.disconnectCallCount)
    }

    @Test
    fun `statusCheck returns null a few times before succeeding, polls at pollIntervalMs apart`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = {
                statusCheckCalls++
                if (statusCheckCalls < 3) null else "final-status"
            },
        )

        assertEquals("final-status", result)
        assertEquals(3, statusCheckCalls)
    }

    @Test
    fun `mqtt message wins mid-polling, no further statusCheck calls after`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { statusCheckCalls++; null },
            )
            assertEquals("paid", result)
        }

        // Let two poll attempts happen (t=5000, t=6000), then push arrives before the third (t=7000).
        advanceTimeBy(6_500L)
        val callsBeforePush = statusCheckCalls
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()

        assertEquals(2, callsBeforePush)
        assertEquals(2, statusCheckCalls)
    }

    @Test
    fun `statusCheck exception is ignored, polling continues on next interval`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)
        var statusCheckCalls = 0

        val result = waiter.await(
            timeoutMs = 5_000L,
            pollIntervalMs = 1_000L,
            matches = { null },
            statusCheck = {
                statusCheckCalls++
                if (statusCheckCalls == 1) error("transient network error") else "final-status"
            },
        )

        assertEquals("final-status", result)
        assertEquals(2, statusCheckCalls)
    }

    @Test
    fun `non-matching mqtt messages do not stop waiting`() = runTest {
        val transport = FakeTransport()
        val waiter = PaymentStatusWaiter(transport)

        val job = launch {
            val result = waiter.await(
                timeoutMs = 5_000L,
                pollIntervalMs = 1_000L,
                matches = { it.payload.takeIf { p -> p == "paid" } },
                statusCheck = { null },
            )
            assertEquals("paid", result)
        }

        transport.emit(RawNotification(topic = "t", payload = "other-invoice"))
        advanceTimeBy(2_000L)
        transport.emit(RawNotification(topic = "t", payload = "paid"))
        job.join()
    }
}
