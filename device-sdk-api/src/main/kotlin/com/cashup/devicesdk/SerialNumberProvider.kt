package com.cashup.devicesdk

/**
 * Nomor seri hardware terminal. Mengembalikan `null` kalau device tidak
 * dikenali SDK vendor mana pun — dalam hal itu provisioning tidak boleh
 * dilanjutkan, karena nomor seri adalah identitas device di backend.
 */
fun interface SerialNumberProvider {
    suspend fun serialNumber(): String?
}
