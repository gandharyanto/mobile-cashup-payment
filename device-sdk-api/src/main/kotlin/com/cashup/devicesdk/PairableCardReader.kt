package com.cashup.devicesdk

data class PairedDeviceInfo(val id: String, val name: String)

/**
 * Kontrak opsional untuk [CardReader] yang butuh device fisik dipilih secara
 * eksplisit sebelum bisa [CardReader.transact] — reader Bluetooth eksternal,
 * bukan terminal built-in. Consumer mengecek lewat `as?`, bukan seluruh
 * [CardReader] mengimplementasikannya.
 */
interface PairableCardReader {
    suspend fun pairedDevices(): List<PairedDeviceInfo>
    suspend fun selectDevice(id: String): Boolean
}
