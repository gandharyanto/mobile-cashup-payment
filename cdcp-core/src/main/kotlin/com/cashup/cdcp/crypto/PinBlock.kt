package com.cashup.cdcp.crypto

/**
 * PIN block format ISO 9564-1 format 0 (ANSI X9.8) -- bentuk yang dipakai terminal kartu.
 * Port BYTE-EXACT dari `edc-simulator/.../crypto/PinBlock.kt`.
 *
 * Formatnya XOR dua blok 8 byte:
 * ```
 * PIN  : 0 L P P P P (P..) F F ...      L = panjang PIN, F = padding
 * PAN  : 0 0 0 0 D D D D D D D D D D D D   12 digit PAN paling kanan TANPA check digit
 * ```
 */
internal object PinBlock {

    private const val BLOCK_SIZE = 8
    private const val PAN_DIGITS = 12
    private const val MIN_PIN_LENGTH = 4
    private const val MAX_PIN_LENGTH = 12

    fun build(pin: String, pan: String): ByteArray {
        require(pin.length in MIN_PIN_LENGTH..MAX_PIN_LENGTH) {
            "Panjang PIN harus $MIN_PIN_LENGTH..$MAX_PIN_LENGTH digit, dapat ${pin.length}"
        }
        require(pin.all(Char::isDigit)) { "PIN harus digit" }
        require(pan.length > PAN_DIGITS && pan.all(Char::isDigit)) { "PAN tidak valid untuk PIN block" }

        val pinField = ("0%X".format(pin.length) + pin).padEnd(BLOCK_SIZE * 2, 'F').hexToBytes()

        // Check digit (paling kanan) dibuang lebih dulu, baru diambil 12 digit terakhir.
        val panField = ("0000" + pan.dropLast(1).takeLast(PAN_DIGITS)).hexToBytes()

        return ByteArray(BLOCK_SIZE) { (pinField[it].toInt() xor panField[it].toInt()).toByte() }
    }
}

