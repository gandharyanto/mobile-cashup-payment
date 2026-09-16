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

internal data class PlainKeyMaterial(val ipek: String, val ksn: String, val kcv: String)

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
            val ipek = decode(purpose, "ipek", material.ipek)
            val ksn = decode(purpose, "ksn", material.ksn)

            // keyCheckValue menol-kan array yang diberikan, jadi dihitung dari
            // salinan -- ipek aslinya masih harus diinjeksi setelah ini.
            val computed = keyCheckValue(ipek.copyOf())
            if (!computed.equals(material.kcv, ignoreCase = true)) {
                ipek.fill(0)
                ksn.fill(0)
                produced.forEach { it.zeroize() }
                throw PackageIntegrityException("KCV tidak cocok untuk purpose $purpose")
            }

            produced += TerminalKeyMaterial(purpose = purpose, ipek = ipek, ksn = ksn)
        }
        return produced
    }

    private fun decode(purpose: String, field: String, value: String): ByteArray = try {
        Base64.decode(value, Base64.NO_WRAP)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field untuk purpose $purpose bukan Base64 yang sah")
    }
}