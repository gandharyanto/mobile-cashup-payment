package com.cashup.app.di

import android.content.Context
import com.cashup.app.AppConfig
import com.cashup.app.BuildConfig
import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import com.cashup.devicesdk.edcsdk.EdcSdkScanner
import com.cashup.devicesdk.edcsdk.EdcSdkSerialNumberProvider
import com.cashup.devicesdk.edcsdk.EdcSdkTerminalKeyInstaller
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
     * `Mgf1Digest.SHA1` adalah asumsi sampai backend mengonfirmasi (spec §8
     * item 1b). Ini yang menentukan apakah key RSA bisa dibuat di TEE — salah
     * menebaknya berarti device mendaftarkan public key yang tidak akan pernah
     * bisa membuka paketnya sendiri, jadi jangan diubah tanpa jawaban dari
     * backend.
     */
    private val rsa = RsaKeyStore(appContext, requiredMgf1 = Mgf1Digest.SHA1)

    val deviceSigner = StoredDeviceSigner(stateStore, ed25519)

    val serialNumbers = EdcSdkSerialNumberProvider(appContext)

    val vendorScanner = EdcSdkScanner(appContext)

    fun isProvisioned(): Boolean = stateStore.current() != null

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
}
