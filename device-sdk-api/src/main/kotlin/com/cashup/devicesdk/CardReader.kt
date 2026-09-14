package com.cashup.devicesdk

enum class CardType { CHIP, TAP, SWIPE }

sealed class CardReadResult {
    data class Success(val trackData: String, val cardType: CardType) : CardReadResult()
    data class Failure(val reason: String) : CardReadResult()
    data object Cancelled : CardReadResult()
}

interface CardReader {
    suspend fun waitForCard(timeoutMillis: Long): CardReadResult
    fun cancel()
}
