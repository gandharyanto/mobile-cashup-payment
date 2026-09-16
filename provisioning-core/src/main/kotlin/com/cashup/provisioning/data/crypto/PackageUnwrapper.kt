package com.cashup.provisioning.crypto

import com.cashup.devicesdk.TerminalKeyMaterial
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.util.Base64

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
class PackageUnwrapper(
    private val unwrapper: RsaUnwrapper,
    private val gson: Gson = Gson(),
) {

    fun unwrap(wrappedPackageKeyBase64: String): List<TerminalKeyMaterial> {
        val wrapped = try {
            Base64.getDecoder().decode(wrappedPackageKeyBase64)
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

        return materials.map { (purpose, material) ->
            val ipek = decode(purpose, "ipek", material.ipek)
            val ksn = decode(purpose, "ksn", material.ksn)

            // keyCheckValue menol-kan array yang diberikan, jadi dihitung dari
            // salinan -- ipek aslinya masih harus diinjeksi setelah ini.
            val computed = keyCheckValue(ipek.copyOf())
            if (!computed.equals(material.kcv, ignoreCase = true)) {
                ipek.fill(0)
                ksn.fill(0)
                throw PackageIntegrityException("KCV tidak cocok untuk purpose $purpose")
            }

            TerminalKeyMaterial(purpose = purpose, ipek = ipek, ksn = ksn)
        }
    }

    private fun decode(purpose: String, field: String, value: String): ByteArray = try {
        Base64.getDecoder().decode(value)
    } catch (e: IllegalArgumentException) {
        throw PackageIntegrityException("Field $field untuk purpose $purpose bukan Base64 yang sah")
    }
}