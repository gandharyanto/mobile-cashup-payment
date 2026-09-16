package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KcvTest {

    private fun key16() = byteArrayOf(
        0x01, 0x23, 0x45, 0x67, 0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0x98.toByte(), 0x76, 0x54, 0x32, 0x10,
    )

    @Test
    fun `returns six uppercase hex characters`() {
        val kcv = keyCheckValue(key16())

        assertEquals(6, kcv.length)
        assertTrue(kcv, kcv.all { it in "0123456789ABCDEF" })
    }

    @Test
    fun `a 16-byte key gives the same KCV as its K1K2K1 expansion`() {
        val double = key16()
        val triple = key16() + key16().copyOfRange(0, 8)

        // Mengunci aturan perluasan. Kalau kelak diubah jadi K1K2K2 atau tidak
        // diperluas sama sekali, tes ini yang jatuh -- bukan bank yang menolak
        // transaksi dengan response code 81 berbulan-bulan kemudian.
        assertEquals(keyCheckValue(triple), keyCheckValue(double))
    }

    @Test
    fun `different keys give different check values`() {
        val other = key16().also { it[0] = 0x02 }

        assertNotEquals(keyCheckValue(key16()), keyCheckValue(other))
    }

    @Test
    fun `the caller's key array is zeroed afterwards`() {
        val key = key16()

        keyCheckValue(key)

        assertEquals(0, key.count { it != 0.toByte() })
    }
}