package com.cashup.provisioning.crypto

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.cashup.provisioning.data.local.SecurePrefs
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import android.util.Log
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/** Digest MGF1 yang dipakai backend saat membungkus paket key. */
enum class Mgf1Digest { SHA1, SHA256 }

enum class RsaKeyLocation { ANDROID_KEYSTORE, SOFTWARE }

data class RsaKeyInfo(val publicKeySpkiBase64: String, val location: RsaKeyLocation)

/**
 * Menentukan lokasi keypair untuk jalur paket TDES_DUKPT lama.
 *
 * Keputusan ini diambil SEKALI, sebelum `qr-redeem`, dan tidak boleh berubah
 * setelahnya: public key yang didaftarkan ke backend harus milik key yang
 * nantinya benar-benar dipakai membuka paket.
 *
 * Batasannya nyata, sudah diverifikasi terhadap `android.jar`:
 * `KeyGenParameterSpec.Builder.setMgf1Digests` baru ada di **API 35**. Di bawah
 * itu, operasi OAEP bawaan AndroidKeyStore memakai MGF1 SHA-1. Token TR-34
 * SHA-256/MGF1 SHA-256 dibuka melalui RSA/NoPadding Keystore dan validasi
 * OAEP terpisah di [unwrapTr34EphemeralKey].
 *
 * Lihat spec §4.5.
 */
object RsaKeyLocationPolicy {
    const val MGF1_DIGESTS_API = 35

    fun choose(required: Mgf1Digest, apiLevel: Int): RsaKeyLocation = when {
        required == Mgf1Digest.SHA1 -> RsaKeyLocation.ANDROID_KEYSTORE
        apiLevel >= MGF1_DIGESTS_API -> RsaKeyLocation.ANDROID_KEYSTORE
        else -> RsaKeyLocation.SOFTWARE
    }
}

/**
 * Keypair RSA-2048 yang membuka bungkusan paket key.
 *
 * Jalur AndroidKeyStore memakai key non-exportable dengan `PURPOSE_DECRYPT`.
 * Pada perangkat software-backed, material key tidak diberi ke API aplikasi,
 * tetapi tidak mendapat isolasi hardware. StrongBox dicoba lebih dulu dan
 * gagalnya ditangani, karena banyak SoC EDC tidak punya.
 *
 * Jalur software ada semata karena batas MGF1 di [RsaKeyLocationPolicy], bukan
 * karena dipilih — dan [RsaKeyInfo.location] melaporkannya supaya kondisi itu
 * terlihat, tidak diam.
 *
 * Tidak ada tes unit untuk kelas ini. Robolectric tidak mengemulasi Android
 * Keystore dengan setia; yang diuji adalah kebijakannya, dan kripto-nya
 * divalidasi manual di terminal fisik.
 */
class RsaKeyStore(
    context: Context,
    private val requiredMgf1: Mgf1Digest = Mgf1Digest.SHA1,
    /** When true, refuse devices that cannot generate this key in StrongBox. */
    private val requireStrongBox: Boolean = false,
    /** When true, refuse software RSA even if the backend digest is incompatible with TEE. */
    private val requireHardwareBacked: Boolean = true,
) {
    private val appContext = context.applicationContext
    private val prefs by lazy { SecurePrefs.open(appContext, PREFS) }

    private val location: RsaKeyLocation
        get() = RsaKeyLocationPolicy.choose(requiredMgf1, Build.VERSION.SDK_INT)

    @Synchronized
    fun ensureKeyPair(): RsaKeyInfo = when (location) {
        RsaKeyLocation.ANDROID_KEYSTORE -> {
            Log.d(TAG, "RSA key requested: backend=${requiredMgf1.name}, api=${Build.VERSION.SDK_INT}, requireStrongBox=$requireStrongBox")
            val publicKey = ensureKeystoreKey()
            val hardwareBacked = verifyHardwareBacked()
            RsaKeyInfo(publicKey, RsaKeyLocation.ANDROID_KEYSTORE)
                .also {
                    if (hardwareBacked) {
                        Log.d(TAG, "RSA key ready: hardware-backed AndroidKeyStore (StrongBox preferred, TEE fallback)")
                    } else {
                        Log.w(TAG, "RSA key ready: software-backed AndroidKeyStore, private key non-exportable")
                    }
                }
        }
        RsaKeyLocation.SOFTWARE -> {
            if (requireHardwareBacked) throw IllegalStateException("RSA software key dilarang; backend/device tidak kompatibel dengan TEE")
            Log.w(TAG, "RSA key using software fallback: api=${Build.VERSION.SDK_INT}, requiredMgf1=${requiredMgf1.name}")
            RsaKeyInfo(ensureSoftwareKey(), RsaKeyLocation.SOFTWARE)
        }
    }

    fun unwrapper(): RsaUnwrapper = RsaUnwrapper { wrapped ->
        val cipher = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE ->
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    init(Cipher.DECRYPT_MODE, keystorePrivateKey(), oaepSpec())
                }
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                Cipher.getInstance("RSA/ECB/OAEPPadding", BcProvider.NAME).apply {
                    init(Cipher.DECRYPT_MODE, softwarePrivateKey(), oaepSpec())
                }
            }
        }
        cipher.doFinal(wrapped)
    }

    /**
     * TR-34 uses OAEP SHA-256 with MGF1 SHA-256. On Android 13 the Keystore
     * OAEP operation cannot authorize that MGF1 digest, so perform only raw
     * RSA inside Keystore and validate the OAEP encoded message in app memory.
     * The private key remains non-exportable; the raw RSA authorization is
     * broader than OAEP-only and must be restricted to this provisioning use.
     */
    fun unwrapTr34EphemeralKey(wrapped: ByteArray): ByteArray {
        val spec = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)
        return when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE -> {
                val privateKey = keystorePrivateKey()
                val publicKey = androidKeyStore().getCertificate(ALIAS).publicKey as java.security.interfaces.RSAPublicKey
                val raw = Cipher.getInstance("RSA/ECB/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, privateKey)
                    Log.d(TAG, "RSA TR-34 raw cipher provider=${provider.name}")
                    doFinal(wrapped)
                }
                try {
                    OaepSha256.decode(raw, (publicKey.modulus.bitLength() + 7) / 8)
                } finally {
                    raw.fill(0)
                }
            }
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                Cipher.getInstance("RSA/ECB/OAEPPadding", BcProvider.NAME).apply {
                    init(Cipher.DECRYPT_MODE, softwarePrivateKey(), spec)
                }.doFinal(wrapped)
            }
        }
    }

    /** Debug-only helper for decrypting Base64 RSA-OAEP ciphertext in the app. */
    fun decryptBase64(ciphertext: String): ByteArray =
        unwrapper().unwrap(Base64.decode(ciphertext.trim(), Base64.DEFAULT))

    /**
     * `SHA256withRSA` — dipakai `ProvisionDeviceUseCase` untuk `deviceSignature`
     * proof-of-possession di `/activate` (spec §2 langkah 6b-i). Key yang sama
     * dengan yang membuka paket; `PURPOSE_SIGN` ditambahkan ke
     * `KeyGenParameterSpec` di bawah supaya ini bekerja di jalur TEE.
     */
    fun sign(bytes: ByteArray): ByteArray {
        val privateKey = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE -> keystorePrivateKey()
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                softwarePrivateKey()
            }
        }
        val provider = if (location == RsaKeyLocation.SOFTWARE) BcProvider.NAME else null
        return (if (provider != null) java.security.Signature.getInstance("SHA256withRSA", provider)
                else java.security.Signature.getInstance("SHA256withRSA")).run {
            initSign(privateKey)
            update(bytes)
            sign()
        }
    }

    /**
     * Sertifikat self-signed atas keypair ini (§4.2 spec 17 September),
     * dibuat sekali dan disimpan — idempoten seperti keypair-nya sendiri,
     * supaya sertifikat yang dikirim ke backend selalu cocok dengan key yang
     * sedang dipakai. Daftar beranggota satu: self-signed, bukan rantai.
     */
    @Synchronized
    fun certificateChain(): List<String> {
        prefs.getString(KEY_CERTIFICATE, null)?.let { return listOf(it) }

        val keyPair = when (location) {
            RsaKeyLocation.ANDROID_KEYSTORE -> java.security.KeyPair(
                androidKeyStore().getCertificate(ALIAS).publicKey,
                keystorePrivateKey(),
            )
            RsaKeyLocation.SOFTWARE -> {
                BcProvider.ensureInstalled()
                java.security.KeyPair(
                    java.security.KeyFactory.getInstance("RSA")
                        .generatePublic(java.security.spec.X509EncodedKeySpec(Base64.decode(prefs.getString(KEY_PUBLIC, null), Base64.NO_WRAP))),
                    softwarePrivateKey(),
                )
            }
        }
        val der = SelfSignedCertificate.build(keyPair)
        val base64 = Base64.encodeToString(der, Base64.NO_WRAP)
        prefs.edit().putString(KEY_CERTIFICATE, base64).commit()
        return listOf(base64)
    }

    /** PKCS#10 CSR signed by the RSA private key for backend KRD issuance. */
    @Synchronized
    fun krdCsr(): String {
        BcProvider.ensureInstalled()
        val publicKey = androidKeyStore().getCertificate(ALIAS)?.publicKey
            ?: throw IllegalStateException("RSA public key tidak tersedia")
        val csr = JcaPKCS10CertificationRequestBuilder(
            X500Name("CN=cashup-edc-device"), publicKey,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keystorePrivateKey()))
        return Base64.encodeToString(csr.encoded, Base64.NO_WRAP)
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC).remove(KEY_CERTIFICATE).commit()
        runCatching {
            androidKeyStore().apply {
                deleteEntry(ALIAS)
                deleteEntry(OLD_ALIAS)
            }
        }
    }

    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        when (requiredMgf1) {
            Mgf1Digest.SHA1 -> MGF1ParameterSpec.SHA1
            Mgf1Digest.SHA256 -> MGF1ParameterSpec.SHA256
        },
        PSource.PSpecified.DEFAULT,
    )

    private fun androidKeyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKeystoreKey(): String {
        if (requireStrongBox && Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            throw IllegalStateException("StrongBox membutuhkan Android 9 (API 28) atau lebih baru")
        }
        val store = androidKeyStore()
        store.getCertificate(ALIAS)?.let {
            Log.d(TAG, "RSA key reused from AndroidKeyStore alias=$ALIAS")
            return it.publicKey.encoded.base64()
        }

        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_SIGN,
        )
            .setKeySize(2048)
            // SHA-256 untuk digest utama OAEP, SHA-1 untuk MGF1. Keduanya harus
            // ada: `unwrapper()` memakai MGF1ParameterSpec.SHA1 (Mgf1Digest.SHA1
            // yang dipatok di §4.5), dan sebagian implementasi AndroidKeyStore
            // ikut memvalidasi digest MGF1 terhadap daftar digest yang
            // diotorisasi key -- menolak `Cipher.init` dengan
            // InvalidAlgorithmParameterException kalau hanya SHA-256 terdaftar.
            // TR-34 memakai RSA/NoPadding Keystore dan validasi OAEP terpisah;
            // kemampuan tersebut diuji sebelum backend menerbitkan order.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_NONE)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_RSA_OAEP,
                KeyProperties.ENCRYPTION_PADDING_NONE,
            )
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
            .setCertificateSubject(X500Principal("CN=cashup-provisioning"))
            .setCertificateSerialNumber(BigInteger.ONE)
            .setCertificateNotBefore(Calendar.getInstance().time)
            .apply {
                if (strongBox && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    setIsStrongBoxBacked(true)
                }
            }
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
        val pair = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

            generateWithStrongBoxFallback(generator) { spec(strongBox = false) } ?: run {
                if (requireStrongBox) {
                    Log.e(TAG, "RSA StrongBox generation unavailable; refusing fallback")
                    throw IllegalStateException("StrongBox tidak tersedia untuk RSA key pair")
                }
                Log.w(TAG, "RSA StrongBox unavailable; falling back to AndroidKeyStore TEE")
                generator.initialize(spec(strongBox = false))
                generator.generateKeyPair()
            }
        } else {
            if (requireStrongBox) throw IllegalStateException("StrongBox tidak tersedia")
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
        try {
            probeTr34Unwrap(pair.public)
        } catch (failure: Exception) {
            runCatching { store.deleteEntry(ALIAS) }
            throw IllegalStateException("RSA Keystore tidak dapat membuka OAEP SHA-256/MGF1 SHA-256 di perangkat ini", failure)
        }
        prefs.edit().remove(KEY_CERTIFICATE).commit()
        return pair.public.encoded.base64()
    }

    private fun probeTr34Unwrap(publicKey: java.security.PublicKey) {
        BcProvider.ensureInstalled()
        val expected = ByteArray(24).also { SecureRandom().nextBytes(it) }
        val wrapped = Cipher.getInstance("RSA/ECB/OAEPPadding", BcProvider.NAME).run {
            init(Cipher.ENCRYPT_MODE, publicKey,
                OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            doFinal(expected)
        }
        try {
            val actual = unwrapTr34EphemeralKey(wrapped)
            try {
                check(expected.contentEquals(actual)) { "RSA TR-34 self-test gagal" }
            } finally {
                actual.fill(0)
            }
        } finally {
            expected.fill(0)
            wrapped.fill(0)
        }
        Log.i(TAG, "RSA TR-34 OAEP SHA-256/MGF1 SHA-256 self-test passed with AndroidKeyStore")
    }

    /** AndroidKeyStore may theoretically use a software provider; reject that for payment keys. */
    private fun verifyHardwareBacked(): Boolean {
        val privateKey = keystorePrivateKey()
        val publicKey = androidKeyStore().getCertificate(ALIAS).publicKey
        val privateEncoded = privateKey.encoded
        Log.d("RSA", "Private format = ${privateKey.format}")
        Log.d("RSA", "Private encoded available = ${privateEncoded != null}, length = ${privateEncoded?.size ?: 0}")
        Log.d("RSA", "Public format = ${publicKey.format}")
        val keyInfo = KeyFactory.getInstance("RSA", "AndroidKeyStore")
            .getKeySpec(privateKey, KeyInfo::class.java)
        if (!keyInfo.isInsideSecureHardware) {
            if (requireHardwareBacked) {
                throw IllegalStateException("RSA private key tidak berada di hardware-backed Android Keystore (TEE/StrongBox)")
            }
            Log.w(TAG, "RSA Keystore is software-backed; private key remains non-exportable by API")
        }
        check(privateEncoded == null) {
            "RSA private key hardware-backed tetapi encoded masih tersedia; provisioning dihentikan"
        }
        if (keyInfo.isInsideSecureHardware) {
            Log.d(TAG, "RSA private key verified hardware-backed: insideSecureHardware=true")
        } else {
            Log.w(TAG, "RSA private key accepted as non-exportable software-backed Keystore fallback")
        }
        return keyInfo.isInsideSecureHardware
    }

    /**
     * `StrongBoxUnavailableException` hanya ada di API 28+. Fungsi ini
     * diisolasi dan diberi anotasi supaya lint tahu pemanggilnya sudah
     * memastikan `Build.VERSION.SDK_INT >= P` -- referensinya di catch clause
     * di bawah ini tidak akan pernah dieksekusi di API lebih rendah.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.P)
    private fun generateWithStrongBoxFallback(
        generator: KeyPairGenerator,
        strongBoxSpec: () -> KeyGenParameterSpec,
    ): java.security.KeyPair? = try {
        generator.initialize(strongBoxSpec())
        generator.generateKeyPair()
    } catch (e: StrongBoxUnavailableException) {
        // Banyak SoC EDC tidak punya StrongBox. TEE biasa tetap jauh lebih
        // baik daripada blob software, jadi ini turun satu tingkat, bukan
        // gagal.
        null
    }

    private fun keystorePrivateKey(): PrivateKey =
        androidKeyStore().getKey(ALIAS, null) as PrivateKey

    private fun ensureSoftwareKey(): String {
        prefs.getString(KEY_PUBLIC, null)?.let { return it }
        BcProvider.ensureInstalled()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        prefs.edit()
            .putString(KEY_PRIVATE, pair.private.encoded.base64())
            .putString(KEY_PUBLIC, pair.public.encoded.base64())
            .commit()
        return pair.public.encoded.base64()
    }

    private fun softwarePrivateKey(): PrivateKey {
        val stored = prefs.getString(KEY_PRIVATE, null)
            ?: error("Keypair RSA belum dibuat")
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.decode(stored, Base64.NO_WRAP)))
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "CashupKeyStore"
        // Alias baru wajib karena izin NoPadding tidak bisa ditambahkan ke key v2.
        const val ALIAS = "cashup_provisioning_rsa_raw_tr34_v3"
        const val OLD_ALIAS = "cashup_provisioning_rsa_strongbox_v2"
        const val PREFS = "provisioning_rsa"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
        const val KEY_CERTIFICATE = "certificate"
    }
}
