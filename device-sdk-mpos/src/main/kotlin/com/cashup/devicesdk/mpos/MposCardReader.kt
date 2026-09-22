package com.cashup.devicesdk.mpos

import android.content.Context
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionEvent
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairableCardReader
import com.cashup.devicesdk.PairedDeviceInfo
import com.lib.device.channel.mpos.MPOSConnector
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import com.lib.device.core.session.DeviceSession
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

/** Reader mPOS Bluetooth eksternal (Newland/Topwise). Perlu [selectDevice] sebelum [transact]. */
class MposCardReader internal constructor(
    private val context: Context,
    private val connectionManager: DeviceConnectionManager,
) : CardReader, PairableCardReader {

    constructor(context: Context) : this(
        context,
        DeviceConnectionManager(listOf(MPOSConnector(context.applicationContext))),
    )

    @Volatile private var gateway: MposEmvGateway? = null
    @Volatile private var active: Continuation<CardReadResult>? = null
    @Volatile private var authorization: CardAuthorization? = null
    private val completed = AtomicBoolean(false)
    private var lastScan: List<DeviceCandidate> = emptyList()

    override suspend fun pairedDevices(): List<PairedDeviceInfo> {
        lastScan = connectionManager.scan(Channel.MPOS)
        return lastScan.map { PairedDeviceInfo(it.id, it.name) }
    }

    override suspend fun selectDevice(id: String): Boolean {
        val candidate = lastScan.find { it.id == id } ?: return false
        return try {
            val session = connectionManager.connect(Channel.MPOS, candidate)
            gateway = RealMposEmvGateway(session)
            session.isAlive
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            gateway = null
            false
        }
    }

    override suspend fun transact(
        request: CardTransactionRequest,
        listener: CardTransactionListener,
    ): CardReadResult {
        val gw = gateway ?: return CardReadResult.Failure("Belum ada reader mPOS terpilih")
        require(request.amount > 0) { "Nominal harus lebih besar dari nol" }
        require(request.timeoutMillis > 0) { "Timeout harus lebih besar dari nol" }
        check(active == null) { "Transaksi kartu lain masih berjalan" }
        completed.set(false)
        authorization = null

        return try {
            withTimeout(request.timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    active = continuation
                    continuation.invokeOnCancellation { gw.stop() }
                    listener.onEvent(CardTransactionEvent.Connecting)
                    listener.onEvent(CardTransactionEvent.WaitingForCard)
                    runCatching { gw.start(Math.multiplyExact(request.amount, 100L), callbacks(listener)) }
                        .onFailure { finish(CardReadResult.Failure(it.message ?: "Gagal memulai EMV")) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            gw.stop()
            CardReadResult.Failure("Waktu membaca kartu habis")
        } finally {
            active = null
        }
    }

    override fun cancel() {
        gateway?.stop()
        finish(CardReadResult.Cancelled)
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
            val selected = listener.selectApplet(applets).coerceIn(applets.indices)
            gateway?.selectApplet(selected)
        }

        override fun onOnline(data: CardTransactionData): String? {
            listener.onEvent(CardTransactionEvent.Authorizing)
            val result = runCatching {
                runBlocking(Dispatchers.IO) { listener.authorize(data) }
            }.getOrElse {
                finish(CardReadResult.Failure(it.message ?: "Otorisasi transaksi gagal"))
                return declineTlv("96")
            }
            authorization = result
            listener.onEvent(CardTransactionEvent.Completing)
            return declineTlv(result.responseCode)
        }

        override fun onError(code: Int, message: String?) {
            gateway?.stop()
            finish(CardReadResult.Failure(message?.takeIf(String::isNotBlank) ?: "EMV gagal ($code)"))
        }

        override fun onFinish() {
            gateway?.stop()
            val result = authorization
            finish(if (result == null) CardReadResult.Failure("EMV selesai tanpa otorisasi host")
            else CardReadResult.Success(result))
        }
    }

    private fun declineTlv(responseCode: String): String {
        val rc = responseCode.padStart(2, '0').takeLast(2)
        return "8A02" + rc.toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }
    }

    private fun finish(result: CardReadResult) {
        if (!completed.compareAndSet(false, true)) return
        val continuation = active
        if (continuation != null) {
            active = null
            continuation.resume(result)
        }
    }
}
