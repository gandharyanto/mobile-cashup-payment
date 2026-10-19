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

    /**
     * Device yang diingat dari sesi sebelumnya, tanpa scan Bluetooth, atau null kalau belum ada.
     * Menyambungkannya tetap lewat [selectDevice] -- dipakai splash untuk menyiapkan reader lebih awal.
     */
    suspend fun rememberedDevice(): PairedDeviceInfo? = null
}
