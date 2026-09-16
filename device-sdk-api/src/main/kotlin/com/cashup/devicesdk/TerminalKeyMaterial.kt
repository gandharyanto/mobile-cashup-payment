package com.cashup.devicesdk

/**
 * Satu key DUKPT hasil unwrap paket provisioning, siap diinjeksi.
 *
 * Sengaja **bukan** `data class`: `toString()` bawaan `data class` akan
 * mencetak isi array, sehingga key material bocor ke setiap log yang
 * kebetulan mencetak objek ini.
 *
 * [ipek] dan [ksn] adalah byte mentah, bukan `String`, supaya bisa dihapus
 * dari memori lewat [zeroize] segera setelah dipakai.
 */
class TerminalKeyMaterial(
    val purpose: String,
    val ipek: ByteArray,
    val ksn: ByteArray,
) {
    /** Menimpa kedua array dengan nol, di tempat. Panggil segera setelah injeksi. */
    fun zeroize() {
        ipek.fill(0)
        ksn.fill(0)
    }

    override fun toString(): String =
        "TerminalKeyMaterial(purpose=$purpose, ipek=<redacted>, ksn=<redacted>)"
}
