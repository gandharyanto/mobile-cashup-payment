package com.cashup.provisioning.crypto

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Key Check Value 3DES: enkripsi ECB delapan byte nol dengan [key], ambil tiga
 * byte pertama, hex huruf besar. Ini yang dikirim ke backend di langkah
 * `activate` sebagai bukti bahwa key yang diterima device sama dengan yang
 * diterbitkan HSM.
 *
 * Key 16 byte (K1K2) diperluas jadi K1K2K1 sebelum dipakai — aturan TDES yang
 * baku. Key 24 byte dipakai apa adanya.
 *
 * [key] di-nol-kan sebelum fungsi ini kembali, termasuk saat gagal. Pemanggil
 * tidak boleh memakainya lagi setelah ini.
 */
fun keyCheckValue(key: ByteArray): String {
    val normalized = if (key.size == 24) key.copyOf() else key + key.copyOfRange(0, 8)
    return try {
        Cipher.getInstance("DESede/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(normalized, "DESede"))
            doFinal(ByteArray(8))
        }.take(3).joinToString("") { "%02X".format(it) }
    } finally {
        key.fill(0)
        normalized.fill(0)
    }
}