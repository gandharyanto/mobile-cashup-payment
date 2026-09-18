package com.cashup.cdcp

import com.cashup.cdcp.crypto.Dukpt
import com.cashup.cdcp.crypto.PinBlock
import com.cashup.cdcp.crypto.padToBlock
import com.cashup.cdcp.crypto.toHex
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.devicesdk.TerminalKeyMaterial
import java.math.BigDecimal

/** Builds the encrypted card payload with the same DUKPT variants as edc-mobile. */
internal class CardPayloadFactory(private val keys: DukptKeyProvider) {
    fun build(deviceId: String, keySetVersion: Int, card: CardDefinition,
              amount: BigDecimal, tip: BigDecimal, pin: String?): CardPayloadRequest {
        require(deviceId.isNotBlank() && keySetVersion > 0)
        require(amount.signum() > 0 && amount.scale() <= 0 && amount.toPlainString().length <= 12)
        require(tip.signum() >= 0 && tip.scale() <= 0 && tip.toPlainString().length <= 12)
        val counter = keys.nextCounter() // persist before any encryption or HTTP request
        val index = Dukpt.ksnIndex(counter)
        val track = requireNotNull(keys.load("TRACK")) { "Key TRACK belum terpasang" }
        val amountKey = requireNotNull(keys.load("AMOUNT")) { "Key AMOUNT belum terpasang" }
        val pinKey = if (pin != null) requireNotNull(keys.load("PIN")) { "Key PIN belum terpasang" } else null
        try {
            fun ksn(material: TerminalKeyMaterial) = Dukpt.composeKsn(material.ksn, index)
            fun encryptAmount(value: BigDecimal): String {
                val digits = value.toBigInteger().toString().padStart(12, '0').padToBlock()
                return Dukpt.encryptData(digits.toByteArray(Charsets.US_ASCII), ksn(amountKey), amountKey.ipek).toHex()
            }
            val track2 = card.track2()
            return CardPayloadRequest(
                keySetVersion = keySetVersion,
                track2Enc = Dukpt.encryptData(track2.padToBlock().toByteArray(Charsets.US_ASCII),
                    ksn(track), track.ipek).toHex(),
                track2Len = track2.length,
                trackKsnIndex = index,
                baseAmountEnc = encryptAmount(amount),
                amountKsnIndex = index,
                tipAmountEnc = tip.takeIf { it.signum() > 0 }?.let(::encryptAmount),
                pinblockEnc = pin?.let { Dukpt.encryptPinBlock(PinBlock.build(it, card.pan),
                    ksn(pinKey!!), pinKey.ipek).toHex() },
                pinKsnIndex = pin?.let { index },
            )
        } finally {
            track.zeroize(); amountKey.zeroize(); pinKey?.zeroize()
        }
    }
}
