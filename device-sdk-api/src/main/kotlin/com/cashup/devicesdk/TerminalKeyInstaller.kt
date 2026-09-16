package com.cashup.devicesdk

/** Di mana sebuah key benar-benar berakhir. Dilaporkan per purpose, tidak pernah diasumsikan. */
enum class KeyBacking {
    /** Modul aman vendor. Key masuk dan tidak pernah keluar. */
    VENDOR_SECURE_MODULE,

    /** Vault ter-enkripsi Keystore. Terlindungi saat diam; key melewati RAM saat dipakai. */
    TEE_VAULT_ONLY,
}

data class KeyInstallOutcome(val purpose: String, val backing: KeyBacking)

sealed interface TerminalKeyInstallResult {
    data class Installed(val outcomes: List<KeyInstallOutcome>) : TerminalKeyInstallResult
    data class Failed(val purpose: String, val reason: String) : TerminalKeyInstallResult
}

/**
 * Memasang key DUKPT ke penyimpanan device.
 *
 * [install] mengembalikan [TerminalKeyInstallResult.Installed] hanya kalau
 * SEMUA material terpasang, dan melaporkan per purpose di mana masing-masing
 * berakhir — pemanggil wajib menampilkan/mencatat itu, karena tidak semua
 * purpose bisa mendapat perlindungan hardware (lihat spec §4.3).
 *
 * [wipe] dipanggil saat rollback atomic: gagal di langkah mana pun membuat
 * device kembali ke keadaan belum terprovisioning, tanpa sisa key separuh jalan.
 *
 * **Batas [wipe] yang harus dibaca sebagai celah, bukan sebagai masalah yang
 * sudah selesai.** [wipe] menghapus apa yang ada di bawah kendali app: vault
 * dan state lokal. Ia TIDAK dan TIDAK BISA menghapus IPEK yang sudah masuk
 * modul aman vendor — modul itu memang dirancang supaya key yang masuk tidak
 * bisa keluar, dan implementasinya (`BaseSystemKey` di AAR `edc-sdk`) tidak
 * mengekspos operasi hapus apa pun, hanya `writeIPEK`. Akibatnya, kegagalan di
 * `ACTIVATE` bisa meninggalkan key hidup di hardware yang backend tidak tahu
 * keberadaannya; yang memulihkannya hanyalah provisioning berikutnya yang
 * berhasil dan menimpa slot itu.
 *
 * Memperbaikinya butuh `BaseSystemKey` diperluas dengan kemampuan hapus dan
 * diimplementasikan ulang per vendor — menyentuh repo `edc-sdk` dan menunggu
 * AAR baru, karena itu **di luar scope plan ini** (spec §4.3, §10). Dicatat di
 * sini, bukan diam-diam ditanggung.
 */
interface TerminalKeyInstaller {
    suspend fun install(materials: List<TerminalKeyMaterial>): TerminalKeyInstallResult
    suspend fun wipe()
}
