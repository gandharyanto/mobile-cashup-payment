package com.cashup.devicesdk

/** Reads the mirrored terminal keys for one transaction. Caller must zeroize the returned arrays. */
interface DukptKeyProvider {
    fun load(purpose: String): TerminalKeyMaterial?
    fun nextCounter(): Int
}
