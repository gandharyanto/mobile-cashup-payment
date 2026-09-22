package com.cashup.app.di

import android.content.Context
import android.util.Log
import android.util.Base64
import com.cashup.app.AppConfig
import com.cashup.app.BuildConfig
import com.cashup.cdcp.SaleRepository
import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import com.cashup.devicesdk.edcsdk.EdcSdkScanner
import com.cashup.devicesdk.edcsdk.EdcSdkSerialNumberProvider
import com.cashup.devicesdk.edcsdk.EdcSdkTerminalKeyInstaller
import com.cashup.devicesdk.edcsdk.EdcSdkDukptKeyProvider
import com.cashup.devicesdk.factory.DeviceSdkFactory
import com.cashup.provisioning.AndroidProvisioningKeys
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.audit.Evidence
import com.cashup.provisioning.audit.ProvisioningJournal
import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.crypto.Mgf1Digest
import com.cashup.provisioning.crypto.RsaKeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.provisioning.data.remote.ProvisioningHttp
import com.cashup.provisioning.domain.ProvisionDeviceUseCase

/**
 * Wiring manual di satu tempat, tanpa framework DI.
 *
 * Hilt akan menambah kapt/ksp dan biaya startup demi keuntungan yang tidak
 * terasa pada app sekecil ini, sementara targetnya terminal 1 GB (spec §6).
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val config = AppConfig(appContext)

    private val logger: PaymentLogger = NoOpPaymentLogger

    init {
        // SEMENTARA -- membuka IPEK plaintext dan private key sepenuhnya di
        // jurnal, untuk mendebug alur di tahap awal. Satu-satunya tempat saklar
        // ini disentuh, dan BuildConfig.DEBUG membuatnya mustahil menyala di
        // build rilis. Dicabut bersama package audit/ (Task 10).
        Evidence.revealSecrets = BuildConfig.DEBUG && BuildConfig.PROVISIONING_JOURNAL
    }

    private val stateStore = ProvisioningStateStore(appContext)
    private val ed25519 = Ed25519KeyStore(appContext)

    /**
     * `Mgf1Digest.SHA1` berlaku untuk paket TDES_DUKPT lama. Token TR-34
     * memakai SHA-256/MGF1 SHA-256 dan dibuka lewat raw RSA Keystore +
     * validasi OAEP di aplikasi, tanpa mengekspor private key.
     */
    private val rsa = RsaKeyStore(
        appContext,
        requiredMgf1 = Mgf1Digest.SHA1,
        // Device target tidak menyediakan StrongBox. RSA tetap dibuat di
        // Android Keystore TEE; StrongBox dicoba opportunistically oleh
        // RsaKeyStore lalu diturunkan ke TEE bila unavailable.
        requireStrongBox = false,
        // Android Keystore pada device ini dilaporkan software-backed, tetapi
        // private handle tetap non-exportable (encoded == null). Izinkan
        // fallback ini; RSA software key biasa tetap dilarang.
        requireHardwareBacked = false,
    )

    /** Generate/load both identity pairs as soon as the application container is created. */
    init {
        runCatching {
            val rsaInfo = rsa.ensureKeyPair()
            Log.i(KEY_TAG, "RSA identity ready at app startup: location=${rsaInfo.location}, publicKey=${rsaInfo.publicKeySpkiBase64}")
            Log.i(KEY_TAG, "RSA PUBLIC_KEY_BASE64=${rsaInfo.publicKeySpkiBase64}")
        }.onFailure { failure ->
            Log.e(KEY_TAG, "RSA identity initialization failed at app startup: ${failure.message}", failure)
        }
        runCatching {
            val ed25519PublicKey = ed25519.ensureKeyPair()
            Log.i(KEY_TAG, "Ed25519 identity ready at app startup: storage=${ed25519.storageDescription()}, publicKey=$ed25519PublicKey")
            Log.i(KEY_TAG, "ED25519 PUBLIC_KEY_BASE64=$ed25519PublicKey")
        }.onFailure { failure ->
            Log.e(KEY_TAG, "Ed25519 identity initialization failed at app startup: ${failure.message}", failure)
        }
    }

    val deviceSigner = StoredDeviceSigner(stateStore, ed25519)

    val serialNumbers = EdcSdkSerialNumberProvider(appContext)

    val vendorScanner = EdcSdkScanner(appContext)

    /**
     * Pemilihan CardReader multi-vendor (EDC built-in atau mPOS Bluetooth).
     * `connect()`-nya suspend dan melakukan I/O nyata -- panggil dari
     * coroutine scope pemanggil (mis. viewModelScope alur sale), bukan di
     * sini. Scanner/SerialNumberProvider/TerminalKeyInstaller/DukptKeyProvider
     * TIDAK lewat sini (spec §5/§8) -- tetap wiring langsung ke
     * device-sdk-edcsdk seperti di atas.
     */
    val deviceSdkFactory = DeviceSdkFactory(appContext)

    fun isProvisioned(): Boolean {
        val identity = stateStore.identity() ?: return false
        return identity.devicePublicKey == ed25519.ensureKeyPair() &&
            stateStore.dukpt()?.deviceId == identity.deviceId
    }

    fun activeDeviceId(): String? = stateStore.identity()?.deviceId

    fun storedSerialNumber(): String? = stateStore.identity()?.serialNumber

    fun decryptRsaBase64(ciphertext: String): ByteArray = rsa.decryptBase64(ciphertext)

    fun signEd25519Base64(message: String): String =
        ed25519.sign(message.toByteArray(Charsets.UTF_8)).let { signature ->
            check(ed25519.verify(message.toByteArray(Charsets.UTF_8), signature)) { "Ed25519 self verification failed" }
            Base64.encodeToString(signature, Base64.NO_WRAP)
        }
    fun activeKeySetVersion(): Int? = stateStore.dukpt()?.keySetVersion

    val saleRepository: SaleRepository by lazy {
        SaleRepository.create(config.baseUrl, deviceSigner, EdcSdkDukptKeyProvider(appContext),
            debugLogging = BuildConfig.DEBUG)
    }

    fun provisionDeviceUseCase(): ProvisionDeviceUseCase {
        val repository = ProvisioningHttp.create(
            baseUrl = config.baseUrl,
            deviceSigner = deviceSigner,
            debugLogging = BuildConfig.DEBUG,
        )
        return ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = serialNumbers,
            keys = AndroidProvisioningKeys(rsa, ed25519),
            installer = EdcSdkTerminalKeyInstaller(appContext),
            state = stateStore,
            // Instance StoredDeviceSigner yang SAMA dengan yang dipakai
            // ProvisioningHttp di atas. Harus sama: use case mengumumkan nomor
            // seri lewat sink ini di langkah 1, dan SigningInterceptor membacanya
            // kembali lewat deviceId() saat menandatangani `/package` dan
            // `/activate`. Dua instance berbeda akan gagal diam-diam sebagai
            // DeviceNotProvisionedException di DOWNLOAD_PACKAGE.
            deviceIdentity = deviceSigner,
            // SEMENTARA -- jurnal hanya diisi di build debug. Dicabut bersama
            // package audit/ sebelum produksi; lihat Task 10.
            journal = ProvisioningJournal(if (BuildConfig.PROVISIONING_JOURNAL) logger else NoOpPaymentLogger),
        )
    }

    private companion object {
        const val KEY_TAG = "CashupKeyStore"
    }
}
