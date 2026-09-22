package com.cashup.cdcp

import com.cashup.cdcp.crypto.Dukpt
import com.cashup.cdcp.crypto.PinBlock
import com.cashup.cdcp.crypto.hexToBytes
import com.cashup.cdcp.crypto.padToBlock
import com.cashup.cdcp.crypto.toHex
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.devicesdk.CardTransactionData
import java.math.BigDecimal

/** Builds the encrypted card payload with the same DUKPT variants as edc-mobile. */
internal class CardPayloadFactory(private val keys: DukptKeyProvider) {
    fun build(deviceId: String, keySetVersion: Int, card: CardDefinition,
              amount: BigDecimal, tip: BigDecimal, pin: String?): CardPayloadRequest {
        val pinBlock = pin?.let { PinBlock.build(it, card.pan) }
        return try {
            build(deviceId, keySetVersion, card.track2(), amount, tip, pinBlock, null)
        } finally {
            pinBlock?.fill(0)
        }
    }

    fun build(deviceId: String, keySetVersion: Int, card: CardTransactionData,
              amount: BigDecimal, tip: BigDecimal): CardPayloadRequest =
        build(
            deviceId = deviceId,
            keySetVersion = keySetVersion,
            track2 = card.track2.replaceFirst('D', '='),
            amount = amount,
            tip = tip,
            pinBlock = card.pinBlock,
            iccData = card.iccData,
        )

    private fun build(deviceId: String, keySetVersion: Int, track2: String,
                      amount: BigDecimal, tip: BigDecimal, pinBlock: ByteArray?,
                      iccData: String?): CardPayloadRequest {
        require(deviceId.isNotBlank() && keySetVersion > 0)
        require(amount.signum() > 0 && amount.scale() <= 0 && amount.toPlainString().length <= 12)
        require(tip.signum() >= 0 && tip.scale() <= 0 && tip.toPlainString().length <= 12)
        require(track2.isNotBlank()) { "Track 2 kartu kosong" }
        require(pinBlock == null || pinBlock.size == 8) { "PIN block dari secure PIN pad tidak valid" }
        val counter = keys.nextCounter() // persist before any encryption or HTTP request
        val index = Dukpt.ksnIndex(counter)
        val track = requireNotNull(keys.load("TRACK")) { "Key TRACK belum terpasang" }
        val amountKey = requireNotNull(keys.load("AMOUNT")) { "Key AMOUNT belum terpasang" }
        val pinKey = if (pinBlock != null) requireNotNull(keys.load("PIN")) { "Key PIN belum terpasang" } else null
        val emvKey = if (iccData != null) requireNotNull(keys.load("EMV")) { "Key EMV belum terpasang" } else null
        try {
            fun ksn(material: TerminalKeyMaterial) = Dukpt.composeKsn(material.ksn, index)
            fun encryptAmount(value: BigDecimal): String {
                val digits = value.toBigInteger().toString().padStart(12, '0').padToBlock()
                return Dukpt.encryptData(digits.toByteArray(Charsets.US_ASCII), ksn(amountKey), amountKey.ipek).toHex()
            }
            return CardPayloadRequest(
                keySetVersion = keySetVersion,
                track2Enc = Dukpt.encryptData(track2.padToBlock().toByteArray(Charsets.US_ASCII),
                    ksn(track), track.ipek).toHex(),
                track2Len = track2.length,
                trackKsnIndex = index,
                baseAmountEnc = encryptAmount(amount),
                amountKsnIndex = index,
                tipAmountEnc = tip.takeIf { it.signum() > 0 }?.let(::encryptAmount),
                pinblockEnc = pinBlock?.let { Dukpt.encryptPinBlock(it, ksn(pinKey!!), pinKey.ipek).toHex() },
                pinKsnIndex = pinBlock?.let { index },
                emvReqEnc = iccData?.let {
                    val bytes = it.hexToBytes().let { raw ->
                        if (raw.size % 8 == 0) raw else raw.copyOf(((raw.size + 7) / 8) * 8)
                    }
                    Dukpt.encryptData(bytes, ksn(emvKey!!), emvKey.ipek).toHex()
                },
                emvReqLen = iccData?.length,
                emvKsnIndex = iccData?.let { index },
            )
        } finally {
            track.zeroize(); amountKey.zeroize(); pinKey?.zeroize(); emvKey?.zeroize()
        }
    }
}
