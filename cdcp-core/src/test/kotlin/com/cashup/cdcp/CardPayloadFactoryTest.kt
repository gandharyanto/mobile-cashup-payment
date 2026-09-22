package com.cashup.cdcp

import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardType
import com.cashup.devicesdk.DukptKeyProvider
import com.cashup.devicesdk.TerminalKeyMaterial
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPayloadFactoryTest {
    @Test fun `physical EMV card uses only the three purposes installed by provisioning`() {
        val loaded = mutableListOf<String>()
        val keys = object : DukptKeyProvider {
            override fun nextCounter() = 1
            override fun load(purpose: String): TerminalKeyMaterial? {
                loaded += purpose
                return TerminalKeyMaterial(
                    purpose,
                    byteArrayOf(0x6A, 0x45, 0x66, 0x6A, 0x53, 0x26, 0x4A, 0x7B,
                        0x2D, 0x17, 0x9D.toByte(), 0x3C, 0x85.toByte(), 0x91.toByte(), 0x8B.toByte(), 0x3A),
                    byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10, 0xE0.toByte(), 0, 0),
                )
            }
        }
        val card = CardTransactionData(
            track2 = "4111111111111111D2812101",
            cardType = CardType.CHIP,
            iccData = "9F2608AABBCCDDEEFF0011",
            pinBlock = ByteArray(8) { 0x11 },
        )

        val payload = CardPayloadFactory(keys).build(
            "device-id", 2, card, BigDecimal("10000"), BigDecimal.ZERO,
        )

        assertEquals(listOf("TRACK", "AMOUNT", "PIN"), loaded)
        assertNotNull(payload.emvReqEnc)
        assertEquals("00001", payload.emvKsnIndex)
        assertEquals(card.iccData!!.length, payload.emvReqLen)
        assertTrue(payload.track2Enc.isNotBlank())
    }
}
