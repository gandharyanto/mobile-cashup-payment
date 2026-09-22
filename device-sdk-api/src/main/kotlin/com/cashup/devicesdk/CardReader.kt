package com.cashup.devicesdk

enum class CardType { CHIP, TAP, SWIPE }

data class CardTransactionRequest(
    /** Rupiah penuh, bukan minor unit/sen. */
    val amount: Long,
    val timeoutMillis: Long = 60_000,
)

data class CardTransactionData(
    val track2: String,
    val cardType: CardType,
    val iccData: String? = null,
    /** ISO-9564 PIN block clear dari secure PIN pad; tidak pernah berisi digit PIN. */
    val pinBlock: ByteArray? = null,
)

data class CardAuthorization(
    val approved: Boolean,
    val responseCode: String,
)

sealed class CardTransactionEvent {
    data object Connecting : CardTransactionEvent()
    data object WaitingForCard : CardTransactionEvent()
    data class CardDetected(val cardType: CardType) : CardTransactionEvent()
    data object PinRequested : CardTransactionEvent()
    data class PinProgress(val length: Int) : CardTransactionEvent()
    data object Authorizing : CardTransactionEvent()
    data object Completing : CardTransactionEvent()
}

interface CardTransactionListener {
    fun onEvent(event: CardTransactionEvent) = Unit
    fun selectApplet(applets: List<String>): Int = 0
    suspend fun authorize(card: CardTransactionData): CardAuthorization
}

sealed class CardReadResult {
    data class Success(val authorization: CardAuthorization) : CardReadResult()
    data class Failure(val reason: String) : CardReadResult()
    data object Cancelled : CardReadResult()
}

interface CardReader {
    /**
     * Menjaga sesi kernel EMV tetap hidup sampai otorisasi host dikembalikan.
     * Implementasi tidak boleh menganggap kartu chip selesai saat track baru terbaca.
     */
    suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener): CardReadResult
    fun cancel()
}
