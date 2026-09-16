package com.cashup.provisioning.crypto

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import com.cashup.provisioning.data.local.SecurePrefs
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.security.auth.x500.X500Principal

/** Digest MGF1 yang dipakai backend saat membungkus paket key. */
enum class Mgf1Digest { SHA1, SHA256 }

enum class RsaKeyLocation { ANDROID_KEYSTORE, SOFTWARE }

data class RsaKeyInfo(val publicKeySpkiBase64: String, val location: RsaKeyLocation)

/**
 * Menentukan di mana keypair RSA dibuat.
 *
 * Keputusan ini diambil SEKALI, sebelum `qr-redeem`, dan tidak boleh berubah
 * setelahnya: public key yang didaftarkan ke backend harus milik key yang
 * nantinya benar-benar dipakai membuka paket.
 *
 * Batasannya nyata, sudah diverifikasi terhadap `android.jar`:
 * `KeyGenParameterSpec.Builder.setMgf1Digests` baru ada di **API 35**. Di bawah
 * itu, digest MGF1 di AndroidKeyStore terkunci SHA-1. Jadi kalau backend
 * membungkus dengan MGF1-SHA256, key TEE tidak akan pernah bisa membukanya di
 * terminal Android 7–11 — dan tidak seperti `edc-mobile`, kita tidak bisa
 * mencoba dua kombinasi karena key hardware hanya punya satu.
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
 * Jalur AndroidKeyStore memakai key non-extractable dengan `PURPOSE_DECRYPT`:
 * unwrap terjadi di dalam TEE dan private key tidak pernah ada di RAM. StrongBox
 * dicoba lebih dulu dan gagalnya ditangani, karena banyak SoC EDC tidak punya.
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
) {
    private val appContext = context.applicationContext
    private val prefs by lazy { SecurePrefs.open(appContext, PREFS) }

    private val location: RsaKeyLocation
        get() = RsaKeyLocationPolicy.choose(requiredMgf1, Build.VERSION.SDK_INT)

    @Synchronized
    fun ensureKeyPair(): RsaKeyInfo = when (location) {
        RsaKeyLocation.ANDROID_KEYSTORE -> RsaKeyInfo(ensureKeystoreKey(), RsaKeyLocation.ANDROID_KEYSTORE)
        RsaKeyLocation.SOFTWARE -> RsaKeyInfo(ensureSoftwareKey(), RsaKeyLocation.SOFTWARE)
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

    @Synchronized
    fun clear() {
        prefs.edit().remove(KEY_PRIVATE).remove(KEY_PUBLIC).remove(KEY_CERTIFICATE).commit()
        runCatching { androidKeyStore().deleteEntry(ALIAS) }
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
        val store = androidKeyStore()
        store.getCertificate(ALIAS)?.let { return it.publicKey.encoded.base64() }

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
            // Key yang tidak bisa membuka paketnya sendiri baru ketahuan di
            // UNWRAP_PACKAGE, setelah backend menerbitkan order.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
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
            generateWithStrongBoxFallback(generator) { spec(strongBox = true) } ?: run {
                generator.initialize(spec(strongBox = false))
                generator.generateKeyPair()
            }
        } else {
            generator.initialize(spec(strongBox = false))
            generator.generateKeyPair()
        }
        return pair.public.encoded.base64()
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
        const val ALIAS = "cashup_provisioning_rsa"
        const val PREFS = "provisioning_rsa"
        const val KEY_PRIVATE = "private_key"
        const val KEY_PUBLIC = "public_key"
        const val KEY_CERTIFICATE = "certificate"
    }
}