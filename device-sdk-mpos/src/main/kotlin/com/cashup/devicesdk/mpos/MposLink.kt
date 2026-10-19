package com.cashup.devicesdk.mpos

import android.content.Context
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardType
import com.cashup.devicesdk.PairedDeviceInfo
import com.cz.anfubridge.AnfuBridge
import com.cz.anfubridge.AnfuCardMode
import com.cz.anfubridge.AnfuPinListener
import com.cz.anfubridge.AnfuReader
import com.cz.anfubridge.AnfuTrackData
import com.cz.anfubridge.AnfuTransactionListener
import com.lib.core.emv.CvmEnum
import com.lib.core.emv.EmvDataConfig
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate

/** Cara [MposCardReader] menemukan reader Bluetooth dan membuka sesi EMV-nya. */
internal interface MposLink {
    suspend fun scan(): List<PairedDeviceInfo>

    /** Gateway EMV untuk reader [id] hasil [scan] terakhir, atau null kalau tidak terhubung. */
    suspend fun select(id: String): MposEmvGateway?
}

/**
 * Newland/Topwise lewat `DeviceConnectionManager` vendor. Reader ANFU ikut muncul di scan yang sama,
 * tetapi koneksi dan EMV-nya dialihkan ke `anfu-bridge` ([AnfuEmvGateway]).
 */
internal class VendorMposLink(
    private val context: Context,
    private val connectionManager: DeviceConnectionManager,
    anfuReader: () -> AnfuReader = { AnfuReader(context.applicationContext) },
) : MposLink {
    private var lastScan: List<DeviceCandidate> = emptyList()
    private val anfu by lazy(anfuReader)

    override suspend fun scan(): List<PairedDeviceInfo> {
        lastScan = connectionManager.scan(Channel.MPOS)
        return lastScan.map { PairedDeviceInfo(it.id, it.name) }
    }

    override suspend fun select(id: String): MposEmvGateway? {
        val candidate = lastScan.find { it.id == id } ?: return null
        return try {
            if (AnfuBridge.isAnfu(candidate.meta["brandKey"] as? String)) selectAnfu(candidate) else selectVendor(candidate)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (unavailable: Exception) {
            null
        }
    }

    private suspend fun selectVendor(candidate: DeviceCandidate): MposEmvGateway? {
        val session = connectionManager.connect(Channel.MPOS, candidate)
        if (!session.isAlive) {
            session.close()
            return null
        }
        return RealMposEmvGateway(context.applicationContext, session)
    }

    private suspend fun selectAnfu(candidate: DeviceCandidate): MposEmvGateway? {
        anfu.connect(candidate.id, candidate.name)
        return if (anfu.isConnected) AnfuEmvGateway(context.applicationContext, anfu) else null
    }
}

/** Adapter EMV di atas `anfu-bridge`; tidak ada tipe vendor ANFU di sini. */
internal class AnfuEmvGateway(context: Context, private val reader: AnfuReader) : MposEmvGateway {
    private val appContext = context.applicationContext
    private var paramsLoaded = false
    private val emv get() = requireNotNull(reader.emv) { "Reader ANFU tidak terhubung" }

    override fun start(amountMinorUnits: Long, callback: EmvCallback) {
        val emv = emv
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
                emv.startPinInput(object : AnfuPinListener {
                    override fun onDisplayPin(length: Int) = callback.onPinProgress(length)
                    override fun onSuccess() = Unit
                    override fun onError(code: Int) = callback.onError(code, "PIN gagal")
                    override fun onTimeout() = callback.onError(PIN_TIMEOUT, "Waktu input PIN habis")
                    override fun onCancel() = callback.onError(PIN_CANCELLED, "Input PIN dibatalkan")
                })
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

        if (!paramsLoaded) {
            emv.loadParams(
                aids = config("emv_parameters.format.json"),
                contactlessAids = config("emvcl_paremeters.format.json"),
                capks = config("capks.format.json"),
            )
            paramsLoaded = true
        }
        emv.startReadCard(amountMinorUnits, listener)
    }

    private fun config(file: String): List<Map<String, String>> =
        EmvDataConfig.getMapConfigs(appContext, file).map { entry ->
            entry.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        }

    override fun stop() {
        try {
            reader.emv?.stop()
        } catch (_: Exception) {
            // Reader boleh sudah putus saat cleanup.
        }
    }

    override fun selectApplet(index: Int) {
        emv.selectApplication(index)
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
    }
}
