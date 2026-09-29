package com.cashup.qris

import com.cashup.common.network.ApiResult
import com.cashup.notification.PaymentNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.RawNotification
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Menunggu status final sebuah QR dynamic: push MQTT sebagai petunjuk cepat, `inquiries` sebagai
 * sumber kebenaran (dokumen API §3/§5). Tidak pernah memanggil `transport.disconnect()`.
 *
 * Dedupe push by `id` (QoS 1 bisa ganda) tidak butuh state di sini: `PaymentStatusWaiter` berhenti
 * mendengarkan pada push cocok pertama, jadi duplikatnya tidak pernah diproses.
 */
class QrisPaymentTracker(
    private val gateway: QrisGateway,
    private val waiter: PaymentStatusWaiter,
    private val transport: PaymentNotificationTransport,
) {
    private val parser = PushParser()

    private sealed class Signal {
        class Push(val detail: QrisPushDetail?) : Signal()
        class Polled(val result: QrisPaymentResult) : Signal()
    }

    /** Suspend sampai hasil final atau QR kedaluwarsa. */
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult {
        val settled = withTimeoutOrNull(expirySeconds * 1_000L + EXPIRY_GRACE_MS) { waitForFinal(qr) }
        if (settled != null) return settled
        return finalOrNull(qr) ?: expired(qr)
    }

    private suspend fun waitForFinal(qr: GeneratedQr): QrisPaymentResult {
        val signal = waiter.await<Signal>(
            timeoutMs = PUSH_WAIT_MS,
            pollIntervalMs = if (transport.isConnected) POLL_WITH_PUSH_MS else POLL_WITHOUT_PUSH_MS,
            matches = { raw -> paidPushFor(qr, raw) },
            statusCheck = { finalOrNull(qr)?.let { Signal.Polled(it) } },
        )
        return when (signal) {
            is Signal.Polled -> signal.result
            is Signal.Push -> (finalOrNull(qr) ?: pollUntilFinal(qr)).copy(pushDetail = signal.detail)
        }
    }

    private fun paidPushFor(qr: GeneratedQr, raw: RawNotification): Signal.Push? {
        val push = parser.parse(raw.payload) ?: return null
        if (push.version != KNOWN_VERSION || push.type != PAID_TYPE || push.module != MODULE) return null
        if (push.transactionId != qr.transactionId) return null
        return Signal.Push(push.detail)
    }

    private suspend fun finalOrNull(qr: GeneratedQr): QrisPaymentResult? =
        (gateway.inquiry(qr.transactionId) as? ApiResult.Success)?.data?.takeIf { it.status.isFinal }

    private suspend fun pollUntilFinal(qr: GeneratedQr): QrisPaymentResult {
        while (true) {
            delay(POLL_WITHOUT_PUSH_MS)
            finalOrNull(qr)?.let { return it }
        }
    }

    private fun expired(qr: GeneratedQr) = QrisPaymentResult(
        transactionId = qr.transactionId,
        status = QrisStatus.EXPIRED,
        amount = qr.baseAmount,
        invoiceNumber = qr.invoiceNumber,
    )

    private companion object {
        const val PUSH_WAIT_MS = 5_000L
        const val POLL_WITH_PUSH_MS = 10_000L
        const val POLL_WITHOUT_PUSH_MS = 3_000L
        const val EXPIRY_GRACE_MS = 5_000L
        const val KNOWN_VERSION = 1
        const val PAID_TYPE = "qris.payment_paid"
        const val MODULE = "qris"
    }
}
