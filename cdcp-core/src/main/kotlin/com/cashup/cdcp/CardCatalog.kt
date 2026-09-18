package com.cashup.cdcp

/**
 * Kartu uji -- identik `EdcProperties.CardDefinition`/`defaultCards()` di `edc-simulator` (PAN,
 * expiry, serviceCode SAMA PERSIS supaya perilaku routing BIN-nya juga sama).
 */
data class CardDefinition(
    val displayName: String,
    val pan: String,
    /** `YYMM`. */
    val expiry: String,
    val serviceCode: String = "101",
    val note: String = "",
) {
    /** `PAN=YYMMSSS` -- separator `=` yang dicari Core saat memisahkan PAN. */
    fun track2(): String = "$pan=$expiry$serviceCode"

    fun maskedPan(): String = pan.take(6) + "*".repeat((pan.length - 10).coerceAtLeast(0)) + pan.takeLast(4)
}

object CardCatalog {
    val cards: List<CardDefinition> = listOf(
        CardDefinition("Uji Lokal", "9999990000123456", "2812", "101", "rentang cadangan, lepas dari BIN nyata"),
        CardDefinition("BNI Debit", "1946800000000008", "2812", "101", "rute nyata -> BNI_DEBIT plain"),
        CardDefinition("Visa", "4111111111111111", "2812", "101", "BIN uji Visa"),
        CardDefinition("Mastercard", "5555555555554444", "2812", "101", "BIN uji Mastercard"),
        CardDefinition("GPN", "6274510000000009", "2812", "101", "BIN GPN/debit lokal"),
    )
}

