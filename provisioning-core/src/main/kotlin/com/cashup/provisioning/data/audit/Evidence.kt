package com.cashup.provisioning.audit

import java.security.MessageDigest

/**
 * Satu-satunya jalan menaruh nilai ke dalam [ProvisioningJournal].
 *
 * Ada supaya aturan "key material tidak pernah masuk log" jadi properti tipe,
 * bukan disiplin: [ProvisioningJournal] hanya menerima `Map<String, String>`,
 * dan satu-satunya cara waras membuat string itu dari sebuah key adalah lewat
 * [secret], yang memang tidak mampu mengeluarkan byte aslinya.
 *
 * Sidik jari dipotong 8 hex (32 bit). Itu cukup untuk keperluan laporan —
 * membuktikan bahwa IPEK yang dipasang sama dengan yang di-unwrap, atau bahwa
 * dua device menerima key berbeda — dan terlalu pendek untuk dibalikkan menjadi
 * key-nya.
 *
 * KCV sengaja TIDAK lewat sini: nilai itu memang dirancang sebagai bukti publik
 * atas sebuah key, dan laporan justru butuh nilai penuhnya untuk dicocokkan
 * dengan catatan HSM.
 */
object Evidence {

    private const val FINGERPRINT_HEX_CHARS = 8

    /**
     * Membuka nilai kelas 2 — IPEK plaintext dan private key — sepenuhnya.
     *
     * **SEMENTARA, KHUSUS DEBUG, MATI SECARA DEFAULT.** Dinyalakan di satu
     * tempat saja ([com.cashup.app.di.AppContainer]), dijaga `BuildConfig.DEBUG`,
     * sehingga di build rilis tidak bisa menyala sama sekali. Uji coba berjalan
     * di terminal sungguhan dengan key DUKPT sungguhan; logcat di sana bukan
     * milik kita.
     *
     * Sengaja `var` global meski itu buruk sebagai desain: seluruh package ini
     * dicabut sebelum produksi, dan menyalurkan flag lewat setiap pemanggil akan
     * membuat pencabutannya menyentuh jauh lebih banyak berkas.
     */
    @Volatile
    @JvmField
    var revealSecrets: Boolean = false

    /**
     * `len=<n> fp=<8 hex>`, ditambah `raw=<hex>` kalau [revealSecrets] menyala.
     * Tidak pernah menyentuh isi [bytes].
     */
    fun secret(bytes: ByteArray): String {
        val base = "len=${bytes.size} fp=${fingerprint(bytes)}"
        return if (revealSecrets) "$base raw=${bytes.joinToString("") { "%02x".format(it) }}" else base
    }

    fun secret(text: String): String = secret(text.toByteArray(Charsets.UTF_8))

    /** Public key itu publik menurut definisi — selalu penuh (kelas 1). */
    fun publicKey(base64: String): String = base64

    /** Token sekali pakai — kelas 1, selalu penuh. Dibutuhkan untuk mencocokkan dengan sisi backend. */
    fun token(value: String): String = value

    /** Ciphertext — kelas 1, selalu penuh. Hanya private key device yang bisa membukanya. */
    fun ciphertext(value: String): String = value

    fun fingerprint(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(FINGERPRINT_HEX_CHARS)
}