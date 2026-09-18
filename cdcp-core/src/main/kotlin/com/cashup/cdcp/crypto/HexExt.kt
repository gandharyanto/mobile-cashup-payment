package com.cashup.cdcp.crypto

/**
 * Hex selalu UPPERCASE saat keluar.
 *
 * Bukan selera: corepayment menyusun KSN penuh dengan `ksnIndex.uppercase()`
 * (`DukptCardCryptoGateway.resolveKey`), jadi indeks yang dikirim huruf kecil akan menurunkan
 * kunci sesi yang berbeda dari yang dipakai Core -- gejalanya "data kartu tidak terbaca", bukan
 * error yang menunjuk ke penyebabnya. Port 1:1 dari `edc-simulator/.../crypto/Hex.kt`.
 */
internal fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

internal fun String.hexToBytes(): ByteArray =
    trim().let { clean ->
        require(clean.length % 2 == 0) { "Panjang hex harus genap, dapat ${clean.length}" }
        ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

