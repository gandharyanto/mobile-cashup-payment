package com.cashup.provisioning.crypto

import com.cashup.shaded.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
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

    private const val BKS_PROVIDER_NAME = "CashupBks"

    /**
     * Provider bawaan "BC" milik Android adalah satu-satunya penyedia KeyStore
     * `BKS`, jenis default Android. OkHttp (lewat `SSLContext.init`) dan
     * library TLS lain memintanya, dan gagal dengan
     * `AssertionError: No System TLS ... BKS not found` kalau hilang. Selama
     * jeda antara [Security.removeProvider] dan [Security.addProvider], thread
     * lain bisa menabrak kondisi itu; karena itu BKS didaftarkan lewat provider
     * terpisah SEBELUM "BC" bawaan dicopot, dan BC pengganti dibuat lebih dulu
     * supaya jeda itu sependek mungkin.
     */
    private class BksProvider : Provider(
        BKS_PROVIDER_NAME,
        1.0,
        "BKS KeyStore kept available while the built-in BC provider is replaced",
    ) {
        init {
            put("KeyStore.BKS", "com.cashup.shaded.bouncycastle.jcajce.provider.keystore.bc.BcKeyStoreSpi\$Std")
        }
    }

    fun ensureInstalled() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val replacement = BouncyCastleProvider()
            if (Security.getProvider(BKS_PROVIDER_NAME) == null) {
                Security.addProvider(BksProvider())
            }
            Security.removeProvider(NAME)
            Security.addProvider(replacement)
            installed = true
        }
    }
}