package com.cashup.feature.qris

import com.cashup.common.network.ApiError
import com.cashup.common.network.ApiResult
import com.cashup.qris.GeneratedQr
import com.cashup.qris.PayApp
import com.cashup.qris.QrSupport
import com.cashup.qris.QrisPaymentResult
import com.cashup.qris.QrisStatus
import java.math.BigDecimal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun app(id: Int, support: QrSupport = QrSupport.DYNAMIC, expiry: Int = 900) =
    PayApp(id, "HOST-$id", "APP-$id", null, support, expiry)

private fun qr(tx: String = "tx-1") =
    GeneratedQr(tx, "000001", BigDecimal("15000"), 2, "APP-2", "Toko", "0002", "APP-a")

private fun outcome(status: QrisStatus) = QrisPaymentResult("tx-1", status, BigDecimal("15000"), rrn = "123")

private class FakeDeps : QrisDependencies {
    var allowed = true
    var apps: ApiResult<List<PayApp>> = ApiResult.Success(listOf(app(2)))
    val generateCalls = mutableListOf<Triple<Int, BigDecimal, String>>()
    val generateResults = mutableListOf<ApiResult<GeneratedQr>>()
    var trackResult: QrisPaymentResult = outcome(QrisStatus.AUTHORIZED)
    var trackGate: CompletableDeferred<QrisPaymentResult>? = null
    var trackedExpiry: Int? = null
    var connectCalls = 0
    var payAppsCalls = 0

    override fun isTransactionAllowed() = allowed
    override suspend fun ensureNotificationsConnected() { connectCalls++ }
    override suspend fun payApps(): ApiResult<List<PayApp>> { payAppsCalls++; return apps }
    override suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr> {
        generateCalls += Triple(qrisHostId, amount, attemptId)
        return if (generateResults.isEmpty()) ApiResult.Success(qr()) else generateResults.removeAt(0)
    }
    override suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult {
        trackedExpiry = expirySeconds
        return trackGate?.await() ?: trackResult
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class QrisViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(deps: FakeDeps, amount: Long = 15_000, clock: () -> Long = { 1_000L }) =
        QrisViewModel(deps, amount, clock, retryDelayMs = 1_000L)

    @Test fun `single dynamic pay app generates immediately and ends PAID`() = runTest {
        val deps = FakeDeps()
        val viewModel = vm(deps)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
        assertEquals(1, deps.connectCalls)
        assertEquals(1, deps.generateCalls.size)
        val (host, amount, attempt) = deps.generateCalls.single()
        assertEquals(2, host)
        assertEquals(BigDecimal("15000"), amount)
        assertTrue(attempt.isNotBlank())
        assertEquals("123", viewModel.state.value.result?.rrn)
        assertEquals(900, deps.trackedExpiry)
    }

    @Test fun `showing qr exposes the qr and a local deadline from qrExpirySeconds`() = runTest {
        val deps = FakeDeps().apply { trackGate = CompletableDeferred() }
        val viewModel = vm(deps, clock = { 1_000L })
        val state = viewModel.state.value
        assertEquals(QrisStage.SHOWING_QR, state.stage)
        assertEquals("0002", state.qr?.qrContent)
        assertEquals(1_000L + 900_000L, state.qrDeadline)
        deps.trackGate!!.complete(outcome(QrisStatus.AUTHORIZED))
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `several dynamic apps ask the user to choose and static-only apps are filtered out`() = runTest {
        val deps = FakeDeps().apply {
            apps = ApiResult.Success(listOf(app(2, QrSupport.DYNAMIC), app(3, QrSupport.BOTH), app(4, QrSupport.STATIC)))
        }
        val viewModel = vm(deps)
        assertEquals(QrisStage.SELECT_APP, viewModel.state.value.stage)
        assertEquals(listOf(2, 3), viewModel.state.value.apps.map { it.qrisHostId })
        assertTrue(deps.generateCalls.isEmpty())
        viewModel.selectApp(3)
        assertEquals(3, deps.generateCalls.single().first)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `no dynamic pay app fails with a clear message`() = runTest {
        val deps = FakeDeps().apply { apps = ApiResult.Success(listOf(app(4, QrSupport.STATIC))) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_no_dynamic, viewModel.state.value.messageRes)
    }

    @Test fun `pay apps failure surfaces the mapped message and code`() = runTest {
        val deps = FakeDeps().apply { apps = ApiResult.Failure(ApiError("ACQUIRER_UNAVAILABLE", "down", 503)) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_error_acquirer_down, viewModel.state.value.messageRes)
        assertEquals("ACQUIRER_UNAVAILABLE", viewModel.state.value.errorCode)
    }

    @Test fun `business failure then retry uses a new attempt id`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("AMOUNT_OUT_OF_RANGE", "x", 422))
        }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_error_amount, viewModel.state.value.messageRes)
        viewModel.retry()
        assertEquals(2, deps.generateCalls.size)
        assertNotEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `host error then retry reloads pay apps to re-pick the provider`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("QRIS_HOST_NOT_ACTIVE", "x", 422))
        }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(1, deps.payAppsCalls)
        viewModel.retry()
        assertEquals(2, deps.payAppsCalls)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `network failure then retry keeps the same attempt id`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("NETWORK", "timeout"))
        }
        val viewModel = vm(deps)
        assertEquals(R.string.qris_error_network, viewModel.state.value.messageRes)
        viewModel.retry()
        assertEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `idempotency in progress is retried once with the same key after a short delay`() = runTest {
        val deps = FakeDeps().apply {
            generateResults += ApiResult.Failure(ApiError("IDEMPOTENCY_KEY_IN_PROGRESS", "wait", 409))
        }
        val viewModel = vm(deps)
        advanceUntilIdle()
        assertEquals(2, deps.generateCalls.size)
        assertEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `expired outcome ends EXPIRED and retry generates a fresh qr`() = runTest {
        val deps = FakeDeps().apply { trackResult = outcome(QrisStatus.EXPIRED) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.EXPIRED, viewModel.state.value.stage)
        deps.trackResult = outcome(QrisStatus.AUTHORIZED)
        viewModel.retry()
        assertEquals(2, deps.generateCalls.size)
        assertNotEquals(deps.generateCalls[0].third, deps.generateCalls[1].third)
        assertEquals(QrisStage.PAID, viewModel.state.value.stage)
    }

    @Test fun `failed acquirer outcome ends FAILED with the generic failure message`() = runTest {
        val deps = FakeDeps().apply { trackResult = outcome(QrisStatus.FAILED) }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_failed, viewModel.state.value.messageRes)
    }

    @Test fun `suspended device never touches the network`() = runTest {
        val deps = FakeDeps().apply { allowed = false }
        val viewModel = vm(deps)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_device_suspended, viewModel.state.value.messageRes)
        assertEquals(0, deps.payAppsCalls)
        assertTrue(deps.generateCalls.isEmpty())
    }

    @Test fun `non positive amount is rejected before any call`() = runTest {
        val deps = FakeDeps()
        val viewModel = vm(deps, amount = 0)
        assertEquals(QrisStage.FAILED, viewModel.state.value.stage)
        assertEquals(R.string.qris_invalid_amount, viewModel.state.value.messageRes)
        assertEquals(0, deps.payAppsCalls)
        assertNull(viewModel.state.value.qr)
    }
}
