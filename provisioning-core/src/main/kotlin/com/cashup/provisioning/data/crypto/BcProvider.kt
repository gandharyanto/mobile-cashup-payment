package com.cashup.provisioning.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

/**
 * Memasang BouncyCastle sekali, dipakai HANYA lewat penyebutan nama eksplisit.
 *
 * Dua jebakan di sini sudah pernah dibayar mahal di `edc-mobile`; keduanya
 * gagal dengan gejala yang menyesatkan, jadi jangan disederhanakan.
 *
 * **Satu: provider bawaan harus dicopot dulu.** Android sudah menyertakan
 * provider bernama `"BC"` sejak lama, tapi versi yang sengaja dipotong Google
 * dan tidak punya banyak algoritma yang kita butuhkan. Akibatnya
 * `Security.getProvider("BC") == null` SELALU false di Android, sehingga pola
 * lazim `if (provider == null) addProvider(...)` tidak pernah benar-benar
 * memasang BC lengkap — app diam-diam memakai versi terpotong dan gagal dengan
 * `NoSuchAlgorithmException`. Karena itu [Security.removeProvider] dipanggil
 * lebih dulu, tanpa syarat.
 *
 * **Dua: prioritasnya harus PALING RENDAH.** `insertProviderAt(bc, 1)` pernah
 * dipakai dan justru merusak jalur lain: setiap `Cipher.getInstance(...)` yang
 * tidak menyebut nama provider ikut dialihkan ke BC, termasuk RSA-OAEP, yang
 * lalu gagal dengan `InvalidCipherTextException: unable to decrypt block` —
 * pesan khas BC yang terlihat seperti masalah data, bukan masalah provider.
 * [Security.addProvider] menaruhnya di urutan terakhir: tersedia kalau dipanggil
 * dengan nama [NAME], tidak mengambil alih resolusi siapa pun.
 */
object BcProvider {

    const val NAME = "BC"

    @Volatile
    private var installed = false

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider(NAME)
            Security.addProvider(BouncyCastleProvider())
            installed = true
        }
    }
}