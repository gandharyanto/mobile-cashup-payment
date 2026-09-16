package com.cashup.provisioning.crypto

import com.cashup.devicesdk.TerminalKeyMaterial
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import android.util.Base64

class PackageIntegrityException(message: String) : Exception(message)

/**
 * Bentuk plaintext di dalam bungkusan RSA.
 *
 * **Belum dikonfirmasi backend** (spec §8 item 1). Kalau ternyata backend
 * memakai skema hibrida seperti `edc-mobile` — RSA membungkus kunci AES,
 * payload sesungguhnya di AES-GCM — yang berubah hanya berkas ini dan
 * `KeyPackageResponse`. Batasnya sengaja sempit supaya perubahan itu murah.
 */
internal data class PlainKeyPackage(val materials: Map<String, PlainKeyMaterial>?)

/**
 * Ketiga field sengaja nullable meski paket yang sah selalu memuatnya.
 *
 * Gson membangun instance lewat `Unsafe`, melewati konstruktor Kotlin sepenuhnya
 * — field non-null yang hilang di JSON tetap terisi `null`, dan pengecekan
 * null-safety Kotlin tidak pernah berjalan. Dideklarasikan non-null, paket
 * malformed akan muncul sebagai NullPointerException tanpa konteks jauh di dalam
 * [PackageUnwrapper.decode]; dideklarasikan nullable, ia muncul sebagai
 * [PackageIntegrityException] yang menyebut purpose dan field-nya.
 */
internal data class PlainKeyMaterial(
    val ipek: String?,
    val ksn: String?,
    val kcv: String?,
)

/**
 * Membuka paket key dan memverifikasinya sebelum apa pun dipasang.
 *
 * Purpose diperlakukan **data-driven**: apa pun nama purpose yang datang akan
 * diteruskan. Tidak ada daftar purpose yang di-hardcode di sini, karena jumlah
 * dan namanya belum dikonfirmasi backend (spec §7.2) — dan menebaknya berarti
 * paket yang sah ditolak diam-diam saat backend menambah satu.
 *
 * KCV tiap purpose dihitung ulang dari IPEK dan dibandingkan dengan yang
 * dikirim backend. Ini satu-satunya kesempatan mendeteksi key yang rusak di
 * perjalanan: begitu IPEK masuk modul aman vendor, ia tidak bisa dibaca lagi.
 * Karena itu satu KCV yang tidak cocok membatalkan **seluruh** paket, bukan
 * hanya purpose itu — pemasangan separuh adalah keadaan yang dilarang spec §7.1.
 */
open class PackageUnwrapper(
    private val unwrapper: RsaUnwrapper,
    private val gson: Gson = Gson(),
) {

    /**
     * **Penyimpangan tercatat dari spec §4.4.** Aturan "key material tidak
     * pernah disalin ke `String`" berlaku di seluruh jalur kripto codebase ini
     * kecuali di sini: langkah decode JSON di bawah menghasilkan `String`
     * perantara — hasil `plaintext.decodeToString()` beserta setiap field
     * `ipek`/`ksn` di [PlainKeyMaterial] — yang berisi IPEK/KSN ter-base64 dan
     * **tidak bisa di-zeroize**. `String` di JVM immutable; keduanya hidup di
     * heap sampai GC memutuskan sebaliknya, dan bisa berakhir di heap dump.
     *
     * Array byte hasil decode-nya tetap diperlakukan benar (di-`fill(0)` di
     * setiap jalur keluar), dan `plaintext` mentahnya dinolkan di `finally`;
     * yang tidak tertangani hanyalah `String` perantara itu.
     *
     * Ini dipaksa oleh asumsi bentuk paket berupa JSON, yang sendirinya belum
     * dikonfirmasi backend (spec §8 item 1). Menulis ulang jalur ini dengan
     * `JsonReader` di atas `CharArrayReader` bisa menghilangkan `String`
     * perantara, tapi itu perubahan yang jauh lebih besar dan hanya masuk akal
     * dikerjakan setelah format paket yang sesungguhnya diketahui — kalau
     * ternyata hibrida AES-GCM seperti `edc-mobile`, seluruh langkah ini
     * berubah bentuk. Diterima apa adanya untuk sekarang, dicatat supaya tidak
     * hilang.
     */
    open fun unwrap(wrappedPackageKeyBase64: String): List<TerminalKeyMaterial> {
        val wrapped = try {
            Base64.decode(wrappedPackageKeyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw PackageIntegrityException("wrappedPackageKey bukan Base64 yang sah")
        }

        val plaintext = unwrapper.unwrap(wrapped)
        val parsed = try {
            gson.fromJson(plaintext.decodeToString(), PlainKeyPackage::class.java)
        } catch (e: JsonSyntaxException) {
            throw PackageIntegrityException("Isi paket key bukan JSON yang dikenali")
        } finally {
            plaintext.fill(0)
        }

        val materials = parsed?.materials
        if (materials.isNullOrEmpty()) {
            throw PackageIntegrityException("Paket key tidak memuat material DUKPT satu pun")
        }

        // Dikumpulkan berjalan supaya, kalau satu purpose gagal KCV-nya,
        // material milik purpose-purpose sebelumnya yang sudah didekode juga
        // ikut di-zeroize -- bukan hanya purpose yang gagal itu sendiri.
        val produced = mutableListOf<TerminalKeyMaterial>()
        for ((purpose, material) in materials) {
            // Ketiga field diperiksa ada lebih dulu, baru didekode: field yang
            // hilang harus gagal sebelum byte key mana pun sempat masuk memori.
            val rawIpek = required(purpose, "ipek", material.ipek)
            val rawKsn = required(purpose, "ksn", material.ksn)
            val kcv = required(purpose, "kcv", material.kcv)

            val ipek = decode(purpose, "ipek", rawIpek)
            val ksn = decode(purpose, "ksn", rawKsn)

            // keyCheckValue menol-kan array yang diberikan, jadi dihitung dari
            // salinan -- ipek aslinya masih harus diinjeksi setelah ini.
            val computed = keyCheckValue(ipek.copyOf())
            if (!computed.equals(kcv, ignoreCase = true)) {
                ipek.fill(0)
                ksn.fill(0)
                produced.forEach { it.zeroize() }
                throw PackageIntegrityException("KCV tidak cocok untuk purpose $purpose")
            }

            produced += TerminalKeyMaterial(purpose = purpose, ipek = ipek, ksn = ksn)
        }
        return produced
    }

    /**
     * Gson menulis `null` lewat `Unsafe` untuk field yang hilang, jadi
     * ketiadaan field harus diperiksa eksplisit di sini — kalau tidak, ia
     * muncul sebagai NullPointerException tanpa konteks alih-alih kegagalan
     * integritas paket yang menyebut apa yang hilang.
     */
    private fun required(purpose: String, field: String, value: String?): String =
        value ?: throw PackageIntegrityException("Field $field untuk purpose $purpose hilang")

    private fun decode(purpose: String, field: String, value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field untuk purpose $purpose bukan Base64 yang sah")
    }
}