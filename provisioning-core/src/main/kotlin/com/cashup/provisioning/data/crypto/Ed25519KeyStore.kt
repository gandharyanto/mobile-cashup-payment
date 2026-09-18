package com.cashup.provisioning.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import com.cashup.provisioning.data.local.SecurePrefs
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import android.util.Log

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

    private val appContext = context.applicationContext

    private val prefs by lazy { SecurePrefs.open(context.applicationContext, PREFS) }

    fun hasKeyPair(): Boolean = prefs.contains(KEY_PRIVATE) ||
        (Build.VERSION.SDK_INT > Build.VERSION_CODES.TIRAMISU && keystore().containsAlias(ALIAS))

    fun storageDescription(): String =
        if (prefs.contains(KEY_PRIVATE)) "EncryptedSharedPreferences" else "AndroidKeyStore"

    /** Public key raw 32 byte, Base64 — bentuk yang diharapkan backend. */
    @Synchronized
    fun ensureKeyPair(): String {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.TIRAMISU) {
            // Android 13 vendor providers may create Ed25519 entries but fail
            // intermittently when getKey() is called from the OkHttp thread.
            // Choose one stable signing backend before public-key enrollment.
            runCatching { keystore().deleteEntry(ALIAS) }
            prefs.getString(KEY_PUBLIC_RAW, null)?.let {
                Log.d(TAG, "Ed25519 key reused from encrypted app storage; Android 13 Keystore Ed25519 disabled")
                return it
            }
            return generateSoftwareKey()
        }
        keystore().getCertificate(ALIAS)?.let { certificate ->
            try {
                verifyKeystoreKey()
                // Some API 33 vendor providers can generate Ed25519 but fail only
                // when a private operation is attempted. Probe signing now so the
                // key is never registered and then unusable for request signing.
                val probe = keystorePrivateKey() ?: error("Ed25519 Keystore private key missing")
                Signature.getInstance("Ed25519").run {
                    initSign(probe)
                    update(byteArrayOf(0x01))
                    sign()
                }
                val rawBase64 = Base64.encodeToString(rawFromX509(certificate.publicKey.encoded), Base64.NO_WRAP)
                Log.d(TAG, "Ed25519 key reused from Android Keystore; non-exportable=${probe.encoded == null}")
                return rawBase64
            } catch (failure: Exception) {
                runCatching { keystore().deleteEntry(ALIAS) }
                Log.w(TAG, "Android Keystore Ed25519 cannot perform signing; falling back to encrypted software key", failure)
            }
        }
        prefs.getString(KEY_PUBLIC_RAW, null)?.let {
            Log.d(TAG, "Ed25519 key reused from encrypted app storage; hardware StrongBox is unavailable for this algorithm")
            return it
        }

        try {
            val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).setDigests(KeyProperties.DIGEST_NONE).setAlgorithmParameterSpec(ECGenParameterSpec("Ed25519")).build())
            val pair = generator.generateKeyPair()
            verifyKeystoreKey()
            Signature.getInstance("Ed25519").run {
                // Do not probe the in-memory object returned by generateKeyPair:
                // some vendor providers can sign with it but cannot reload the
                // same Ed25519 entry through AndroidKeyStore later.
                initSign(keystorePrivateKey())
                update(byteArrayOf(0x01))
                sign()
            }
            val rawBase64 = Base64.encodeToString(rawFromX509(pair.public.encoded), Base64.NO_WRAP)
            Log.i(TAG, "Ed25519 key generated in Android Keystore; non-exportable=true")
            return rawBase64
        } catch (failure: Exception) {
            runCatching { keystore().deleteEntry(ALIAS) }
            Log.w(TAG, "Android Keystore Ed25519 unavailable; using encrypted software fallback", failure)
        }

        return generateSoftwareKey()
    }

    private fun generateSoftwareKey(): String {
        Log.d(TAG, "Generating Ed25519 key with BouncyCastle encrypted software fallback")
        BcProvider.ensureInstalled()
        val pair = KeyPairGenerator.getInstance("Ed25519", BcProvider.NAME).generateKeyPair()
        val privateEncoded = pair.private.encoded
        Log.d("ED25519", "Private format = ${pair.private.format}")
        Log.d("ED25519", "Private encoded available = ${privateEncoded != null}, length = ${privateEncoded?.size ?: 0}")
        Log.d("ED25519", "Public format = ${pair.public.format}")
        val raw = rawFromX509(pair.public.encoded)
        val rawBase64 = Base64.encodeToString(raw, Base64.NO_WRAP)

        prefs.edit()
            .putString(KEY_PRIVATE, Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUBLIC_RAW, rawBase64)
            .commit()
        Log.d(TAG, "Ed25519 key generated and stored encrypted; private material was not logged")
        return rawBase64
    }

    fun sign(bytes: ByteArray): ByteArray {
        prefs.getString(KEY_PRIVATE, null)?.let { stored ->
            BcProvider.ensureInstalled()
            val privateKey = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
                .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP)))
            return Signature.getInstance("Ed25519", BcProvider.NAME).run {
                initSign(privateKey)
                update(bytes)
                sign()
            }
        }
        keystorePrivateKey()?.let { privateKey ->
            return Signature.getInstance("Ed25519").run {
                initSign(privateKey)
                update(bytes)
                sign()
            }
        }
        error("Keypair Ed25519 belum dibuat")
    }

    /** Debug verification using the public key held by the same provider. */
    fun verify(bytes: ByteArray, signature: ByteArray): Boolean {
        val stored = prefs.getString(KEY_PUBLIC_RAW, null)
        val publicEncoded = if (stored != null) {
            val raw = Base64.decode(stored, Base64.NO_WRAP)
            byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) + raw
        } else {
            keystore().getCertificate(ALIAS)?.publicKey?.encoded ?: return false
        }
        BcProvider.ensureInstalled()
        val publicKey = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
            .generatePublic(java.security.spec.X509EncodedKeySpec(publicEncoded))
        return Signature.getInstance("Ed25519", BcProvider.NAME).run {
            initVerify(publicKey)
            update(bytes)
            verify(signature)
        }
    }

    @Synchronized
    fun clear() {
        keystore().deleteEntry(ALIAS)
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC_RAW).commit()
    }

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun keystorePrivateKey() = keystore().getKey(ALIAS, null) as? java.security.PrivateKey

    private fun verifyKeystoreKey() {
        val privateKey = keystorePrivateKey() ?: error("Ed25519 Android Keystore private key missing")
        val encoded = privateKey.encoded
        Log.d("ED25519", "Private format = ${privateKey.format}")
        Log.d("ED25519", "Private encoded available = ${encoded != null}, length = ${encoded?.size ?: 0}")
        val publicKey = keystore().getCertificate(ALIAS).publicKey
        Log.d("ED25519", "Public format = ${publicKey.format}")
        check(encoded == null) { "Ed25519 private key encoded tersedia; key tidak non-exportable" }
        runCatching {
            val info = KeyFactory.getInstance("EC", "AndroidKeyStore").getKeySpec(privateKey, KeyInfo::class.java)
            Log.d(TAG, "Ed25519 Android Keystore security: insideSecureHardware=${info.isInsideSecureHardware}")
        }.onFailure { Log.w(TAG, "Ed25519 KeyInfo unavailable; non-exportability still verified by encoded=null", it) }
    }

    /**
     * SubjectPublicKeyInfo X.509 untuk Ed25519 selalu 44 byte: prefix ASN.1
     * tetap 12 byte diikuti 32 byte key mentah. Backend memakai konvensi raw
     * yang sama di arah sebaliknya.
     */
    private fun rawFromX509(encoded: ByteArray): ByteArray =
        encoded.copyOfRange(encoded.size - 32, encoded.size)

    private companion object {
        const val TAG = "CashupKeyStore"
        const val PREFS = "provisioning_ed25519"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC_RAW = "public_key_raw"
        const val ALIAS = "cashup_provisioning_ed25519_keystore_v1"
    }
}
