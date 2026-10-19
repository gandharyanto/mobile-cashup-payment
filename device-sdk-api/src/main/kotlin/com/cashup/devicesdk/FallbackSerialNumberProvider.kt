package com.cashup.devicesdk

/**
 * Mencoba [primary] dulu (serial hardware terminal EDC), lalu [fallback] kalau
 * hasilnya `null` -- mis. di HP biasa yang tidak dikenali SDK vendor mana pun.
 */
class FallbackSerialNumberProvider(
    private val primary: SerialNumberProvider,
    private val fallback: SerialNumberProvider,
) : SerialNumberProvider {
    override suspend fun serialNumber(): String? =
        primary.serialNumber() ?: fallback.serialNumber()
}
