package com.cashup.devicesdk.mpos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardType
import com.cashup.devicesdk.PairedDeviceInfo
import com.cz.anfubridge.AnfuBridge
import com.cz.anfubridge.AnfuCardMode
import com.cz.anfubridge.AnfuEmv
import com.cz.anfubridge.AnfuKeySession
import com.cz.anfubridge.AnfuPinListener
import com.cz.anfubridge.AnfuReader
import com.cz.anfubridge.AnfuTrackData
import com.cz.anfubridge.AnfuTransactionListener
import com.lib.core.emv.CvmEnum
import com.lib.core.emv.EmvDataConfig
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/** Cara [MposCardReader] menemukan reader Bluetooth dan membuka sesi EMV-nya. */
internal interface MposLink {
    suspend fun scan(): List<PairedDeviceInfo>

    /** Gateway EMV untuk reader [id] hasil [scan] terakhir, atau null kalau tidak terhubung. */
    suspend fun select(id: String): MposEmvGateway?

    /** Reader yang diingat dari sesi sebelumnya; [scan] hanya menawarkan reader ini. */
    fun remembered(): PairedDeviceInfo? = null

    /** True kalau reader [id] masih terhubung dan sudah disiapkan oleh [select]. */
    fun isReady(id: String): Boolean = false
}

/**
 * Newland/Topwise lewat `DeviceConnectionManager` vendor. Reader ANFU ikut muncul di scan yang sama,
 * tetapi koneksi dan EMV-nya dialihkan ke `anfu-bridge` ([AnfuEmvGateway]).
 *
 * Reader ANFU pertama yang berhasil terhubung dan siap disimpan di [savedReaders]. Sesudah itu
 * [scan] hanya menawarkan reader tersimpan itu, tanpa scan Bluetooth, dan [select] langsung
 * menghubungkannya.
 */
internal class VendorMposLink(
    private val context: Context,
    private val connectionManager: DeviceConnectionManager,
    anfuReader: () -> AnfuReader = { AnfuReader(context.applicationContext) },
    private val savedReaders: SavedReaderStore = SharedPrefsSavedReaderStore(context),
    private val prepareReader: suspend (AnfuEmvGateway) -> Unit = { it.prepare() },
) : MposLink {
    private var lastScan: List<DeviceCandidate> = emptyList()
    private val anfu by lazy(anfuReader)
    @Volatile private var readyId: String? = null

    override fun remembered(): PairedDeviceInfo? =
        savedReaders.get()?.let { PairedDeviceInfo(it.id, it.name) }

    override fun isReady(id: String): Boolean = readyId == id && anfu.isConnected

    override suspend fun scan(): List<PairedDeviceInfo> {
        savedReaders.get()?.let { saved ->
            lastScan = emptyList()
            return listOf(PairedDeviceInfo(saved.id, saved.name))
        }
        lastScan = connectionManager.scan(Channel.MPOS)
        return lastScan.map { PairedDeviceInfo(it.id, it.name) }
    }

    override suspend fun select(id: String): MposEmvGateway? {
        val saved = savedReaders.get()?.takeIf { it.id == id }
        if (saved != null) return connecting { connectAnfu(saved.id, saved.name) }

        val candidate = lastScan.find { it.id == id } ?: return null
        return connecting {
            if (AnfuBridge.isAnfu(candidate.meta["brandKey"] as? String)) connectAnfu(candidate.id, candidate.name)
            else selectVendor(candidate)
        }
    }

    private suspend fun connecting(connect: suspend () -> MposEmvGateway?): MposEmvGateway? = try {
        connect()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (unavailable: Exception) {
        null
    }

    private suspend fun selectVendor(candidate: DeviceCandidate): MposEmvGateway? {
        val session = connectionManager.connect(Channel.MPOS, candidate)
        if (!session.isAlive) {
            session.close()
            return null
        }
        return RealMposEmvGateway(context.applicationContext, session)
    }

    private suspend fun connectAnfu(id: String, name: String): MposEmvGateway? {
        anfu.connect(id, name)
        if (!anfu.isConnected) return null
        val gateway = AnfuEmvGateway(context.applicationContext, anfu)
        readyId = null
        prepareReader(gateway)
        if (savedReaders.get() == null) savedReaders.save(SavedReader(id, name))
        readyId = id
        return gateway
    }
}

/** Adapter EMV di atas `anfu-bridge`; tidak ada tipe vendor ANFU di sini. */
internal class AnfuEmvGateway(
    context: Context,
    private val reader: AnfuReader,
    private val newKey: () -> ByteArray = { ByteArray(KEY_LENGTH).also(SecureRandom()::nextBytes) },
    private val readConfig: (String) -> List<Map<String, String>> = { file ->
        EmvDataConfig.getMapConfigs(context.applicationContext, file).map { entry ->
            entry.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        }
    },
) : MposEmvGateway {
    private val appContext = context.applicationContext
    private var paramsLoaded = false
    private var disconnectWatcher: BroadcastReceiver? = null
    private val emv get() = requireNotNull(reader.emv) { "Reader ANFU tidak terhubung" }
    private val keySession get() = requireNotNull(reader.keySession) { "Reader ANFU tidak terhubung" }
    private val params get() = requireNotNull(reader.params) { "Reader ANFU tidak terhubung" }

    /**
     * Menyiapkan reader sekarang (±40 detik), bukan menunggu `startReadCard`: memasang hierarki key
     * ANFU lalu mendorong AID/CAPK. Selama berjalan `keySession` ditandai sedang injeksi, supaya
     * [awaitReady] menahan transaksi.
     */
    suspend fun prepare() = withContext(Dispatchers.IO) { prepareReader() }

    override suspend fun awaitReady(onPreparing: () -> Unit) {
        val keys = keySession
        if (keys.isInjectionInProgress) {
            onPreparing()
            var waited = 0L
            while (keys.isInjectionInProgress && waited < READY_TIMEOUT_MILLIS) {
                delay(READY_POLL_MILLIS)
                waited += READY_POLL_MILLIS
            }
            check(!keys.isInjectionInProgress) { "Reader belum siap. Coba lagi." }
        }
        // Sesi key/CAPK reader kedaluwarsa kalau idle terlalu lama atau sempat putus.
        if (keys.needsPreparation()) {
            onPreparing()
            prepare()
        }
    }

    private suspend fun prepareReader() {
        val keys = keySession
        keys.markInjectionStarted()
        var prepared = false
        try {
            installKeys(keys)
            loadParamsOnce(emv)
            // pushToReader melapor sukses walau cache kosong, jadi cache diperiksa sendiri.
            check(params.cachedAidCount > 0 && params.cachedCapkCount > 0) {
                "Parameter EMV (AID/CAPK) tidak termuat ke cache reader"
            }
            check(params.pushToReader()) { "Reader menolak parameter EMV (AID/CAPK)" }
            prepared = true
        } finally {
            if (prepared) keys.markInjectionFinished() else keys.markInjectionFailed()
        }
    }

    /**
     * Hierarki key ANFU: transport key (TK) -> KEK -> PIK. Ketiganya hanya untuk hubungan app dan
     * reader: reader mengunci PIN dengan PIK, library vendor membukanya lagi di HP, lalu PIN dienkripsi
     * ulang dengan IPEK DUKPT hasil provisioning (`CardPayloadFactory`). Bank tidak pernah melihat
     * ketiganya, jadi KEK dan PIK dibuat acak setiap sesi, bukan konstanta di kode.
     *
     * TK reader ini bernilai nol semua (terbukti diterima reader). `startReadCard` vendor menolak
     * jalan kalau TK dan KEK belum ter-cache.
     */
    private fun installKeys(keys: AnfuKeySession) {
        check(keys.writeTransportKey(ZERO_TRANSPORT_KEY, ZERO_TRANSPORT_KEY)) { "Reader menolak transport key" }
        val kek = newKey()
        val pik = newKey()
        try {
            // Versi satu argumen: memuat TK dari cache dan men-cache KEK. Versi dua argumen tidak men-cache,
            // sehingga writePik jatuh ke jalur plaintext dan pushKeys vendor menolak jalan.
            check(keys.writeKek(kek)) { "Reader menolak KEK" }
            check(keys.writePik(pik)) { "Reader menolak PIK" }
        } finally {
            kek.fill(0)
            pik.fill(0)
        }
    }

    private fun loadParamsOnce(emv: AnfuEmv) {
        if (paramsLoaded) return
        // initParams, bukan loadParams: hanya initParams yang mengisi cache AID/CAPK yang didorong ke reader.
        emv.initParams(
            context = appContext,
            aids = readConfig("emv_parameters.format.json"),
            contactlessAids = readConfig("emvcl_paremeters.format.json"),
            capks = readConfig("capks.format.json"),
        )
        paramsLoaded = true
    }

    override fun start(amountMinorUnits: Long, callback: EmvCallback) {
        val emv = emv
        watchDisconnect(callback)
        val listener = object : AnfuTransactionListener {
            override fun onSearchCard(mode: AnfuCardMode, track: AnfuTrackData, cardInfo: String) {
                callback.onCard(snapshot(mode, track, cardInfo))
            }

            override fun onPinEntry(type: Int) {
                val cvm = CvmEnum.values().getOrNull(type) ?: CvmEnum.EMV_CVMFLAG_NO_CVM
                if (cvm == CvmEnum.EMV_CVMFLAG_NO_CVM || cvm == CvmEnum.EMV_CVMFLAG_SIGNATURE) {
                    emv.skipPin()
                    return
                }
                callback.onPinRequested()
                // Exception dari library vendor di callback ini mematikan proses (thread utama),
                // jadi dilaporkan sebagai kegagalan transaksi biasa.
                try {
                    ensureLedHelper()
                    emv.startPinInput(object : AnfuPinListener {
                        override fun onDisplayPin(length: Int) = callback.onPinProgress(length)
                        override fun onSuccess() = Unit
                        override fun onError(code: Int) = callback.onError(code, "PIN gagal")
                        override fun onTimeout() = callback.onError(PIN_TIMEOUT, "Waktu input PIN habis")
                        override fun onCancel() = callback.onError(PIN_CANCELLED, "Input PIN dibatalkan")
                    })
                } catch (failure: Exception) {
                    callback.onError(PIN_UNAVAILABLE, "Input PIN tidak dapat dimulai")
                }
            }

            override fun onError(code: Int, message: String) = callback.onError(code, message)
            override fun onFinish() = callback.onFinish()

            override fun onOnlineProcess(emvData: String): String {
                val card = emv.cardData
                val data = snapshot(
                    card.mode ?: AnfuCardMode.IC,
                    card.track ?: AnfuTrackData(null, null, null),
                    card.iccData,
                )
                return callback.onOnline(data).orEmpty()
            }

            override fun onApplicationSelect(names: List<String>) = callback.onAppletSelection(names)
        }

        loadParamsOnce(emv)
        emv.startReadCard(amountMinorUnits, listener)
    }

    override fun stop() {
        unwatchDisconnect()
        try {
            reader.emv?.stop()
        } catch (_: Exception) {
            // Reader boleh sudah putus saat cleanup.
        }
    }

    override fun selectApplet(index: Int) {
        emv.selectApplication(index)
    }

    /** Bluetooth putus di tengah transaksi: hentikan sekarang, jangan menunggu timeout EMV vendor. */
    private fun watchDisconnect(callback: EmvCallback) {
        unwatchDisconnect()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!intent.getBooleanExtra(AnfuBridge.EXTRA_CONNECTED, false)) {
                    callback.onError(READER_DISCONNECTED, "Koneksi reader terputus")
                }
            }
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(AnfuBridge.ACTION_READER_STATE), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        disconnectWatcher = receiver
    }

    private fun unwatchDisconnect() {
        val receiver = disconnectWatcher ?: return
        disconnectWatcher = null
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // Sudah dilepas.
        }
    }

    private fun snapshot(mode: AnfuCardMode, tracks: AnfuTrackData, iccData: String?): CardTransactionData {
        val card = emv.cardData
        val track2 = tracks.track2
            ?: card.track?.track2
            ?: emv.getTagList(intArrayOf(0x57))?.let(::track2FromTag57)
            ?: ""
        return CardTransactionData(
            track2 = track2.trimEnd('F'),
            cardType = when (mode) {
                AnfuCardMode.IC -> CardType.CHIP
                AnfuCardMode.RF -> CardType.TAP
                AnfuCardMode.MAG -> CardType.SWIPE
            },
            iccData = iccData ?: card.iccData,
            pinBlock = card.pinBlockHex
                ?.takeUnless { it.equals("FFFFFFFFFFFFFFFF", ignoreCase = true) }
                ?.hexToBytesOrNull(),
        )
    }

    private fun track2FromTag57(value: String): String =
        value.removePrefix("57").let { encoded ->
            if (encoded.length >= 2 && encoded.take(2).toIntOrNull(16) != null) encoded.drop(2) else encoded
        }.trimEnd('F')

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || !all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private companion object {
        const val PIN_TIMEOUT = -10_002
        const val PIN_CANCELLED = -10_003
        const val READER_DISCONNECTED = -10_004
        const val PIN_UNAVAILABLE = -10_005
        const val READY_TIMEOUT_MILLIS = 45_000L // key ~5 dtk + AID/CAPK ~35 dtk
        const val READY_POLL_MILLIS = 200L
        const val KEY_LENGTH = 16
        const val ZERO_TRANSPORT_KEY = "00000000000000000000000000000000"
    }
}
