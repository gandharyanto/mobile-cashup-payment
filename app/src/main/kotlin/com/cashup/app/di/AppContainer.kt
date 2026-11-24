package com.cashup.app.di

import android.content.Context
import android.util.Log
import android.util.Base64
import com.cashup.app.AppConfig
import com.cashup.app.BuildConfig
import com.cashup.app.R
import com.cashup.cdcp.data.CardActionRepository
import com.cashup.cdcp.data.CardSettlementRepository
import com.cashup.cdcp.data.CardTransactionRepository
import com.cashup.cdcp.data.SaleRepository
import com.cashup.cdcp.data.toSaleCard
import com.cashup.cdcp.domain.CardActionResult
import com.cashup.cdcp.domain.CardTransactionPage
import com.cashup.cdcp.domain.SaleOutcome
import com.cashup.cdcp.domain.SettlementAttempt
import com.cashup.cdcp.domain.SaleRequest
import com.cashup.common.logging.NoOpPaymentLogger
import com.cashup.common.logging.PaymentLogger
import com.cashup.common.network.TransportPolicy
import com.cashup.devicesdk.FallbackSerialNumberProvider
import com.cashup.devicesdk.SerialNumberProvider
import com.cashup.devicesdk.edcsdk.AndroidIdSerialNumberProvider
import com.cashup.devicesdk.edcsdk.EdcSdkScanner
import com.cashup.devicesdk.edcsdk.EdcSdkSerialNumberProvider
import com.cashup.devicesdk.edcsdk.EdcSdkTerminalKeyInstaller
import com.cashup.devicesdk.edcsdk.EdcSdkDukptKeyProvider
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionData
import com.cashup.feature.cardpayment.CardPaymentDependencies
import com.cashup.reader.ReaderSession
import com.cashup.feature.qris.QrisDependencies
import com.cashup.notification.MqttNotificationTransport
import com.cashup.notification.PaymentStatusWaiter
import com.cashup.notification.TlsTrust
import com.cashup.qris.data.GsonPushParser
import com.cashup.qris.data.NotificationQrisSignalSource
import com.cashup.qris.data.QrisRepository
import com.cashup.qris.domain.QrisPaymentTracker
import com.cashup.kernel.CashupResult
import com.cashup.kernel.Money
import com.cashup.provisioning.AndroidProvisioningKeys
import com.cashup.provisioning.StoredDeviceSigner
import com.cashup.provisioning.data.AndroidBase64Codec
import com.cashup.provisioning.data.DeviceTerminalKeyInstallPort
import com.cashup.provisioning.data.asSerialNumberSource
import com.cashup.provisioning.data.audit.Evidence
import com.cashup.provisioning.data.audit.ProvisioningJournal
import com.cashup.provisioning.data.crypto.Ed25519KeyStore
import com.cashup.provisioning.data.crypto.Mgf1Digest
import com.cashup.provisioning.data.crypto.RsaKeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.provisioning.data.remote.ProvisioningHttp
import com.cashup.provisioning.domain.ProvisionDeviceUseCase

/**
 * Wiring manual di satu tempat, tanpa framework DI.
 *
 * Hilt akan menambah kapt/ksp dan biaya startup demi keuntungan yang tidak
 * terasa pada app sekecil ini, sementara targetnya terminal 1 GB (spec §6).
 */
class AppContainer(context: Context) : CardPaymentDependencies {

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
            Log.i(KEY_TAG, "RSA identity ready at app startup: location=${rsaInfo.location}")
            if (BuildConfig.DEBUG) Log.i(
                KEY_TAG,
                "RSA PUBLIC_KEY_BASE64=${rsaInfo.publicKeySpkiBase64}"
            )
        }.onFailure { failure ->
            Log.e(
                KEY_TAG,
                "RSA identity initialization failed at app startup: ${failure.message}",
                failure
            )
        }
        runCatching {
            val ed25519PublicKey = ed25519.ensureKeyPair()
            Log.i(
                KEY_TAG,
                "Ed25519 identity ready at app startup: storage=${ed25519.storageDescription()}"
            )
            if (BuildConfig.DEBUG) Log.i(KEY_TAG, "ED25519 PUBLIC_KEY_BASE64=$ed25519PublicKey")
        }.onFailure { failure ->
            Log.e(
                KEY_TAG,
                "Ed25519 identity initialization failed at app startup: ${failure.message}",
                failure
            )
        }
    }

    val deviceSigner = StoredDeviceSigner(stateStore, ed25519)

    // EDC terminal: serial hardware dari SDK vendor. HP biasa (tidak ada SDK vendor): ANDROID_ID.
    // TEMP (debug builds only): serial dipaksa ke unit uji. BuildConfig.DEBUG menjamin release tetap memakai serial hardware.
    val serialNumbers: SerialNumberProvider = if (DEBUG_SERIAL_OVERRIDE == "") {
        FallbackSerialNumberProvider(
            primary = EdcSdkSerialNumberProvider(appContext),
            fallback = AndroidIdSerialNumberProvider(appContext),
        )
    } else {
        SerialNumberProvider { DEBUG_SERIAL_OVERRIDE }
    }

    val vendorScanner = EdcSdkScanner(appContext)

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
            check(
                ed25519.verify(
                    message.toByteArray(Charsets.UTF_8),
                    signature
                )
            ) { "Ed25519 self verification failed" }
            Base64.encodeToString(signature, Base64.NO_WRAP)
        }

    fun activeKeySetVersion(): Int? = stateStore.dukpt()?.keySetVersion

    val saleRepository: SaleRepository by lazy {
        SaleRepository.create(
            config.baseUrl, deviceSigner, EdcSdkDukptKeyProvider(appContext),
            debugLogging = BuildConfig.DEBUG, transport = transportPolicy
        )
    }

    private val cardTransactionRepository: CardTransactionRepository by lazy {
        CardTransactionRepository.create(
            config.baseUrl, deviceSigner,
            debugLogging = BuildConfig.DEBUG, transport = transportPolicy
        )
    }

    private val cardActionRepository: CardActionRepository by lazy {
        CardActionRepository.create(
            config.baseUrl, deviceSigner,
            debugLogging = BuildConfig.DEBUG, transport = transportPolicy
        )
    }

    private val cardSettlementRepository: CardSettlementRepository by lazy {
        CardSettlementRepository.create(
            config.baseUrl, deviceSigner,
            debugLogging = BuildConfig.DEBUG, transport = transportPolicy
        )
    }

    val qrisDependencies: QrisDependencies by lazy {
        val gateway = QrisRepository.create(
            config.baseUrl,
            deviceSigner,
            debugLogging = BuildConfig.DEBUG,
            transport = transportPolicy,
        )
        // Broker staging memakai sertifikat CA privat (mkcert); dipercaya hanya untuk soket MQTT ini.
        val mqttTls = appContext.resources.openRawResource(R.raw.mqtt_ca)
            .use { TlsTrust.socketFactoryFor(it) }
        val transport = MqttNotificationTransport(config.mqttHost, config.mqttPort, mqttTls)
        AppQrisDependencies(
            gateway = gateway,
            tracker = QrisPaymentTracker(
                gateway,
                NotificationQrisSignalSource(PaymentStatusWaiter(transport), transport),
                GsonPushParser(),
            ),
            transport = transport,
            deviceId = this::activeDeviceId,
            transactionAllowed = this::isTransactionAllowed,
        )
    }

    @Suppress("DEPRECATION")
    override fun isTransactionAllowed(): Boolean = runCatching {
        appContext.packageManager.getApplicationInfo(appContext.packageName, 0).enabled
    }.getOrDefault(false)

    /** Reader untuk T6D (EDC bawaan) dan ANFU (mPOS): satu pintu, dipakai splash dan layar pembayaran. */
    val readerSession = ReaderSession.create(appContext)

    override suspend fun cardReader(): CardReader = readerSession.cardReader()

    override fun prepareReader() {
        readerSession.prepareInBackground()
    }

    override suspend fun authorize(
        card: CardTransactionData,
        amount: Money,
        tip: Money,
        idempotencyKey: String,
    ): CashupResult<SaleOutcome> {
        val deviceId = requireNotNull(activeDeviceId()) { "Identitas device belum tersedia" }
        val keySetVersion =
            requireNotNull(activeKeySetVersion()) { "Key DUKPT provisioning belum aktif" }
        return saleRepository.sale(
            SaleRequest(
                deviceId,
                keySetVersion,
                card.toSaleCard(),
                amount,
                tip
            ), idempotencyKey
        )
    }

    override suspend fun cardTransactions(
        cursor: String?,
        limit: Int,
        fromMillis: Long?,
        toMillis: Long?,
    ): CashupResult<CardTransactionPage> =
        cardTransactionRepository.history(cursor, limit, fromMillis, toMillis)

    override suspend fun voidTransaction(
        invoiceNumber: String,
        attemptId: String,
    ): CashupResult<CardActionResult> = cardActionRepository.void(invoiceNumber, attemptId)

    override suspend fun reverseTransaction(
        invoiceNumber: String,
        attemptId: String,
    ): CashupResult<CardActionResult> = cardActionRepository.reversal(invoiceNumber, attemptId)

    override suspend fun settle(
        acquirerHostCode: String?,
        attemptId: String,
    ): CashupResult<SettlementAttempt> = cardSettlementRepository.settle(acquirerHostCode, attemptId)

    fun provisionDeviceUseCase(): ProvisionDeviceUseCase {
        val repository = ProvisioningHttp.create(
            baseUrl = config.baseUrl,
            deviceSigner = deviceSigner,
            debugLogging = BuildConfig.DEBUG,
            transport = transportPolicy,
        )
        val keys = AndroidProvisioningKeys(rsa, ed25519, packageSigningKeys(), kdhPublicKeys())
        return ProvisionDeviceUseCase(
            gateway = repository,
            serialNumbers = serialNumbers.asSerialNumberSource(),
            keys = keys,
            packageOpener = keys,
            installer = DeviceTerminalKeyInstallPort(EdcSdkTerminalKeyInstaller(appContext)),
            state = stateStore,
            base64 = AndroidBase64Codec,
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

    /** HTTPS only; plain http just for the development hosts of a debug build; pins from the build (N-2). */
    private val transportPolicy = TransportPolicy(
        cleartextHosts = TransportPolicy.parseHosts(BuildConfig.CLEARTEXT_HOSTS),
        pins = TransportPolicy.parsePins(BuildConfig.CERTIFICATE_PINS),
        // A non-debug build never runs unpinned: no pin for the base URL host means the clients refuse to start.
        requirePinnedHosts = !BuildConfig.DEBUG,
        networkInterceptors = DevNetworkInspector.interceptors(appContext),
    )

    /**
     * Pinned signers of key packages (N-3), from the build (`cashup.packageSigningKeys`, comma separated Base64 X.509).
     * Public keys, not secrets. Nothing configured means no package is accepted, so provisioning fails closed.
     */
    private fun packageSigningKeys(): List<ByteArray> = decodeKeys(BuildConfig.PACKAGE_SIGNING_KEYS)

    /** Pinned TR-34 KDH leaf keys (N-3), `cashup.kdhPublicKeys`; none means no TR-34 package is accepted. */
    private fun kdhPublicKeys(): List<ByteArray> = decodeKeys(BuildConfig.KDH_PUBLIC_KEYS)

    private fun decodeKeys(spec: String): List<ByteArray> =
        spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .map { Base64.decode(it, Base64.DEFAULT) }

    private companion object {
        const val KEY_TAG = "CashupKeyStore"
        const val DEBUG_SERIAL_OVERRIDE = "" //CIMB P653200052395
    }
}
