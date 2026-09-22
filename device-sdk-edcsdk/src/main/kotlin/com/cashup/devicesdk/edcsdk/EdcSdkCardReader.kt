package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionEvent
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/** Adapter satu-satunya dari kontrak transaksi aplikasi ke kernel EMV multi-vendor `edc-sdk`. */
class EdcSdkCardReader internal constructor(private val gateway: EmvGateway) : CardReader {
    constructor(context: Context) : this(RealEmvGateway(context))

    @Volatile private var active: Continuation<CardReadResult>? = null
    @Volatile private var authorization: CardAuthorization? = null
    private val completed = AtomicBoolean(false)

    override suspend fun transact(
        request: CardTransactionRequest,
        listener: CardTransactionListener,
    ): CardReadResult {
        require(request.amount > 0) { "Nominal harus lebih besar dari nol" }
        require(request.timeoutMillis > 0) { "Timeout harus lebih besar dari nol" }
        check(active == null) { "Transaksi kartu lain masih berjalan" }
        completed.set(false)
        authorization = null

        return try {
            withTimeout(request.timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    active = continuation
                    continuation.invokeOnCancellation {
                        if (completed.compareAndSet(false, true)) gateway.stop()
                    }
                    listener.onEvent(CardTransactionEvent.Connecting)
                    gateway.connect { connected ->
                        if (!continuation.isActive) return@connect
                        if (!connected) {
                            finish(CardReadResult.Failure("SDK vendor tidak dapat terhubung"))
                            return@connect
                        }
                        listener.onEvent(CardTransactionEvent.WaitingForCard)
                        runCatching { gateway.start(request.amount, callbacks(listener)) }
                            .onFailure { finish(CardReadResult.Failure(it.message ?: "Gagal memulai EMV")) }
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            CardReadResult.Failure("Waktu membaca kartu habis")
        } finally {
            active = null
        }
    }

    override fun cancel() {
        finish(CardReadResult.Cancelled, stopGateway = true)
    }

    private fun callbacks(listener: CardTransactionListener) = object : EmvCallback {
        override fun onCard(data: CardTransactionData) {
            if (data.track2.isBlank()) {
                finish(CardReadResult.Failure("Track 2 kartu tidak terbaca"))
                return
            }
            listener.onEvent(CardTransactionEvent.CardDetected(data.cardType))
        }

        override fun onPinRequested() = listener.onEvent(CardTransactionEvent.PinRequested)
        override fun onPinProgress(length: Int) = listener.onEvent(CardTransactionEvent.PinProgress(length))

        override fun onAppletSelection(applets: List<String>) {
            if (applets.isEmpty()) {
                finish(CardReadResult.Failure("Kernel EMV tidak mengirim pilihan aplikasi kartu"))
                return
            }
            val selected = listener.selectApplet(applets).coerceIn(applets.indices)
            gateway.selectApplet(selected)
        }

        override fun onOnline(data: CardTransactionData): String? {
            listener.onEvent(CardTransactionEvent.Authorizing)
            val result = try {
                runBlocking(Dispatchers.IO) { listener.authorize(data) }
            } catch (failure: Exception) {
                finish(CardReadResult.Failure(failure.message ?: "Otorisasi transaksi gagal"))
                return declineTlv("96")
            }
            authorization = result
            listener.onEvent(CardTransactionEvent.Completing)
            return declineTlv(result.responseCode)
        }

        override fun onError(code: Int, message: String?) {
            finish(
                CardReadResult.Failure(message?.takeIf(String::isNotBlank) ?: "EMV gagal ($code)"),
                stopGateway = true,
            )
        }

        override fun onFinish() {
            val result = authorization
            finish(
                if (result == null) CardReadResult.Failure("EMV selesai tanpa otorisasi host")
                else CardReadResult.Success(result),
                stopGateway = true,
            )
        }
    }

    private fun declineTlv(responseCode: String): String {
        val rc = responseCode.padStart(2, '0').takeLast(2)
        return "8A02" + rc.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }
    }

    private fun finish(result: CardReadResult, stopGateway: Boolean = false) {
        if (!completed.compareAndSet(false, true)) return
        // Kunci status terminal sebelum cleanup. SDK Topwise memanggil callback
        // onError lagi secara sinkron dari stopEmv(); tanpa urutan ini callback
        // akan re-entrant sampai service Binder vendor mati.
        if (stopGateway) gateway.stop()
        val continuation = active
        if (continuation != null) {
            active = null
            continuation.resume(result)
        }
    }
}
