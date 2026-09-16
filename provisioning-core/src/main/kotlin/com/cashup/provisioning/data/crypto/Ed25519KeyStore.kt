package com.cashup.provisioning.crypto

import android.content.Context
import com.cashup.provisioning.data.local.SecurePrefs
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64

/**
 * Keypair Ed25519 yang menandatangani setiap request setelah provisioning.
 *
 * **Ini bukan key hardware-backed, dan tidak bisa dibuat begitu.**
 * `KeyProperties.KEY_ALGORITHM_ED25519` tidak ada di Android — diverifikasi
 * terhadap `android.jar` API 33, 36, dan 37; API 37 menambah ML-DSA dan tetap
 * tanpa Ed25519. Jadi keypair dibuat lewat BouncyCastle dan private key-nya
 * disimpan sebagai blob PKCS8 di [SecurePrefs].
 *
 * Tingkat perlindungannya setara `EncryptedSharedPreferences` biasa, bukan
 * lebih. Ini konsekuensi dari algoritma yang sudah dikunci backend, bukan
 * pilihan — lihat spec §4.2.
 *
 * [ensureKeyPair] idempoten: kalau key sudah ada, tidak dibuat ulang, supaya
 * device tetap dikenali backend.
 */
class Ed25519KeyStore(context: Context) {

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }

    fun hasKeyPair(): Boolean = prefs.contains(KEY_PRIVATE)

    /** Public key raw 32 byte, Base64 — bentuk yang diharapkan backend. */
    @Synchronized
    fun ensureKeyPair(): String {
        prefs.getString(KEY_PUBLIC_RAW, null)?.let { return it }

        BcProvider.ensureInstalled()
        val pair = KeyPairGenerator.getInstance("Ed25519", BcProvider.NAME).generateKeyPair()
        val raw = rawFromX509(pair.public.encoded)
        val rawBase64 = Base64.encodeToString(raw, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC_RAW, rawBase64)
            .commit()
        return rawBase64
    }

    fun sign(bytes: ByteArray): ByteArray {
        val stored = prefs.getString(KEY_PRIVATE, null)
            ?: error("Keypair Ed25519 belum dibuat")
        BcProvider.ensureInstalled()
        val privateKey = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP)))
        return Signature.getInstance("Ed25519", BcProvider.NAME).run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC_RAW).commit()
    }

    /**
     * SubjectPublicKeyInfo X.509 untuk Ed25519 selalu 44 byte: prefix ASN.1
     * tetap 12 byte diikuti 32 byte key mentah. Backend memakai konvensi raw
     * yang sama di arah sebaliknya.
     */
    private fun rawFromX509(encoded: ByteArray): ByteArray =
        encoded.copyOfRange(encoded.size - 32, encoded.size)

    private companion object {
        const val PREFS = "provisioning_ed25519"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC_RAW = "public_key_raw"
    }
}