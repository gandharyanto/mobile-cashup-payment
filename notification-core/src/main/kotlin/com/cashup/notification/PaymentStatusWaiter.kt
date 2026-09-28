package com.cashup.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.selects.select

/**
 * Kebijakan "tunggu push [timeoutMs], lalu fallback REST tiap [pollIntervalMs]
 * SELAMANYA sampai dapat hasil final atau push menang duluan" -- independen
 * dari detail MQTT, testable dengan fake [PaymentNotificationTransport].
 *
 * TIDAK pernah memanggil [PaymentNotificationTransport.disconnect] -- itu
 * tanggung jawab pemilik lifecycle transport, bukan modul ini (spec §3/§5).
 */
class PaymentStatusWaiter(private val transport: PaymentNotificationTransport) {

    /**
     * @param matches dipanggil untuk tiap [RawNotification] yang masuk selama
     *   SELURUH pemanggilan ini (bukan cuma [timeoutMs] pertama); kembalikan
     *   hasil ter-parse kalau pesan ini yang dicari, `null` kalau bukan.
     * @param statusCheck dipanggil pertama kali persis saat [timeoutMs]
     *   terlampaui tanpa [matches] yang berhasil, lalu diulang setiap
     *   [pollIntervalMs] sampai mengembalikan non-null atau [matches] menang
     *   duluan. Exception dari [statusCheck] diabaikan dan di-retry pada
     *   percobaan berikutnya (kecuali [CancellationException], yang harus
     *   tetap propagate supaya cancel dari race ini bekerja).
     * @return hasil pertama yang non-null, dari [matches] atau dari salah
     *   satu pemanggilan [statusCheck] -- mana pun yang menang duluan.
     */
    suspend fun <T> await(
        timeoutMs: Long = 5_000L,
        pollIntervalMs: Long = 1_000L,
        matches: (RawNotification) -> T?,
        statusCheck: suspend () -> T?,
    ): T = coroutineScope {
        val fromMqtt = async { transport.messages.mapNotNull(matches).first() }
        val fromPoll = async {
            delay(timeoutMs)
            pollUntilFound(pollIntervalMs, statusCheck)
        }
        val winner = select<T> {
            fromMqtt.onAwait { it }
            fromPoll.onAwait { it }
        }
        fromMqtt.cancel()
        fromPoll.cancel()
        winner
    }

    private suspend fun <T> pollUntilFound(pollIntervalMs: Long, statusCheck: suspend () -> T?): T {
        while (true) {
            val result: T? = try {
                statusCheck()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (result != null) return result
            delay(pollIntervalMs)
        }
    }
}
