package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardType
import com.lib.core.SDKManager
import com.lib.core.emv.CardModeType
import com.lib.core.emv.CvmEnum
import com.lib.core.emv.EmvDataConfig
import com.lib.core.emv.PinConfig
import com.lib.core.emv.PinInputListener
import com.lib.core.emv.TrackData
import com.lib.core.emv.TransactionResponse

internal interface EmvCallback {
    fun onCard(data: CardTransactionData)
    fun onPinRequested()
    fun onPinProgress(length: Int)
    fun onAppletSelection(applets: List<String>)
    fun onOnline(data: CardTransactionData): String?
    fun onError(code: Int, message: String?)
    fun onFinish()
}

internal interface EmvGateway {
    fun connect(callback: (Boolean) -> Unit)
    fun start(amount: Long, callback: EmvCallback)
    fun stop()
    fun selectApplet(index: Int)
}

internal class RealEmvGateway(context: Context) : EmvGateway {
    private val appContext = context.applicationContext

    override fun connect(callback: (Boolean) -> Unit) {
        SDKManager.autoDetectDevice(appContext, callback)
    }

    override fun start(amount: Long, callback: EmvCallback) {
        val emv = SDKManager.requireHelper().emv

        val response = object : TransactionResponse {
            override fun onSearchCard(cardType: CardModeType, trackData: TrackData, iccData: String?) {
                callback.onCard(snapshot(cardType, trackData, iccData))
            }

            override fun onPinEntry(code: Int) {
                val cvm = CvmEnum.values().getOrNull(code) ?: CvmEnum.EMV_CVMFLAG_NO_CVM
                if (cvm == CvmEnum.EMV_CVMFLAG_NO_CVM || cvm == CvmEnum.EMV_CVMFLAG_SIGNATURE) {
                    emv.skipPin()
                    return
                }
                callback.onPinRequested()
                emv.startPinInput(
                    PinConfig(
                        isKeyboardDefault = true,
                        pinLen = 6,
                        pan = emv.cardData.pan,
                        isRandom = true,
                        pinMin = 4,
                        timeOut = 60,
                    ),
                    object : PinInputListener {
                        override fun onDisplayPin(pin: String) = callback.onPinProgress(pin.length)
                        override fun onSuccess(pinBlock: ByteArray) = Unit
                        override fun onError(code: Int) = callback.onError(code, "PIN gagal")
                        override fun onTimeout() = callback.onError(PIN_TIMEOUT, "Waktu input PIN habis")
                        override fun onCancel() = callback.onError(PIN_CANCELLED, "Input PIN dibatalkan")
                    },
                )
            }

            override fun onError(code: Int, message: String?) = callback.onError(code, message)
            override fun onFinish() = callback.onFinish()

            override fun onOnlineProcess(tlv: String?): String? {
                val data = snapshot(
                    emv.cardData.cardModeType ?: CardModeType.IC,
                    emv.cardData.trackData ?: TrackData(),
                    emv.cardData.iccData,
                )
                return callback.onOnline(data)
            }

            override fun appletSelect(list: List<String>) = callback.onAppletSelection(list)
        }

        if (!emv.isConfiguration) {
            emv.loadParams(
                EmvDataConfig.getMapConfigs(appContext, "emv_parameters.format.json"),
                EmvDataConfig.getMapConfigs(appContext, "emvcl_paremeters.format.json"),
                EmvDataConfig.getMapConfigs(appContext, "capks.format.json"),
            )
        }
        // SDK memakai ISO amount 12 digit dalam minor unit. Rupiah tidak punya pecahan,
        // sehingga nilai transaksi dikali 100 sebelum masuk kernel.
        emv.startReadCard(Math.multiplyExact(amount, 100L), response)
    }

    override fun stop() {
        try {
            SDKManager.helper?.emv?.stopEmv()
        } catch (_: Exception) {
            // Binder vendor boleh sudah putus saat cleanup. Error tetap dipropagasi.
        }
    }

    override fun selectApplet(index: Int) {
        SDKManager.requireHelper().emv.setApplet(index)
    }

    private fun snapshot(mode: CardModeType, tracks: TrackData, iccData: String?): CardTransactionData {
        val emv = SDKManager.requireHelper().emv
        val track2 = tracks.track2Data
            ?: emv.cardData.trackData?.track2Data
            ?: emv.getTagList(intArrayOf(0x57))?.let(::track2FromTag57)
            ?: ""
        return CardTransactionData(
            track2 = track2.trimEnd('F'),
            cardType = when (mode) {
                CardModeType.IC -> CardType.CHIP
                CardModeType.RF -> CardType.TAP
                CardModeType.MAG -> CardType.SWIPE
            },
            iccData = iccData ?: emv.cardData.iccData,
            pinBlock = emv.cardData.pinBlock
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
