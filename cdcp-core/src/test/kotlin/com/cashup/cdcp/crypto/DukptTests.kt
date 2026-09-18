package com.cashup.cdcp.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bukti bahwa port DUKPT di app ini setia terhadap implementasi `edc-simulator` dan `corepayment`.
 *
 * Vektor uji di bawah disalin PERSIS dari
 * `edc-simulator/src/test/kotlin/id/cashup/edcsimulator/crypto/DukptEncryptTests.kt` (nilai yang
 * sama juga dipakai `DukptTests` di `corepayment`). Kalau berkas ini gagal, port-nya salah --
 * jangan "diperbaiki" supaya lolos, cari selisihnya terhadap sumber asli.
 */
class DukptTests {

    private val bdk = "0123456789ABCDEFFEDCBA9876543210".hexToBytes()
    private val ksnCounter1 = "FFFF9876543210E00001".hexToBytes()

    @Test
    fun `generates the published ipek test vector`() {
        assertEquals("6AC292FAA1315B4D858AB3A3D7D5933A", Dukpt.generateIpek(ksnCounter1, bdk).toHex())
    }

    @Test
    fun `derives the published session key for transaction counter one`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        assertEquals("042666B49184CFA368DE9628D0397BC9", Dukpt.deriveKeyFromIpek(ksnCounter1, ipek).toHex())
    }

    @Test
    fun `different transaction counters derive different session keys`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        val keys =
            listOf("FFFF9876543210E00001", "FFFF9876543210E00002", "FFFF9876543210E00003")
                .map { Dukpt.deriveKeyFromIpek(it.hexToBytes(), ipek).toHex() }

        assertEquals(3, keys.toSet().size)
    }

    /** Penyimpangan #2 dari X9.24 buku teks: konstanta varian dikenakan ke KEDUA paruh kunci. */
    @Test
    fun `data and pin variants apply to both halves`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        val derived = Dukpt.deriveKeyFromIpek(ksnCounter1, ipek)

        assertEquals(
            derived.toHex(),
            Dukpt.dataEncryptionKey(derived).xorWith("0000000000FF00000000000000FF0000").toHex(),
        )
        assertEquals(
            derived.toHex(),
            Dukpt.pinEncryptionKey(derived).xorWith("00000000000000FF00000000000000FF").toHex(),
        )
    }

    /** Penyimpangan #1: jalur data melewati `encrypt3DesEcb(dataKey, dataKey)`, jalur PIN tidak. */
    @Test
    fun `data encryption round trips through the self encrypted key step`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        val plaintext = "1234567890ABCDEF1122334455667788".hexToBytes()

        val ciphertext = Dukpt.encryptData(plaintext, ksnCounter1, ipek)
        assertTrue(plaintext.contentEquals(Dukpt.decryptData(ciphertext, ksnCounter1, ipek)))

        assertFalse(plaintext.toHex() == Dukpt.decryptPinBlock(ciphertext, ksnCounter1, ipek).toHex())
    }

    @Test
    fun `pin block encryption round trips without the self encrypt step`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        val pinBlock = PinBlock.build(pin = "1234", pan = "9999990000123456")

        val encrypted = Dukpt.encryptPinBlock(pinBlock, ksnCounter1, ipek)
        assertTrue(pinBlock.contentEquals(Dukpt.decryptPinBlock(encrypted, ksnCounter1, ipek)))
    }

    @Test
    fun `composes the full ksn by replacing the last five hex digits`() {
        val baseKsn = "FFFF9876543210E00000".hexToBytes()

        assertEquals("FFFF9876543210E00007", Dukpt.composeKsn(baseKsn, "00007").toHex())
        assertEquals(Dukpt.KSN_LENGTH, Dukpt.composeKsn(baseKsn, "00007").size)
        assertEquals(7L, Dukpt.transactionCounter(Dukpt.composeKsn(baseKsn, "00007")))
    }

    @Test
    fun `ksn index is normalised to uppercase`() {
        val baseKsn = "FFFF9876543210E00000".hexToBytes()

        assertEquals(
            Dukpt.composeKsn(baseKsn, "000AB").toHex(),
            Dukpt.composeKsn(baseKsn, "000ab").toHex(),
        )
    }

    @Test
    fun `ksn index formats to five uppercase hex digits`() {
        assertEquals("00001", Dukpt.ksnIndex(1))
        assertEquals("000AB", Dukpt.ksnIndex(171))
        assertEquals("1FFFF", Dukpt.ksnIndex(0x1FFFF))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a counter beyond twenty one bits`() {
        Dukpt.ksnIndex(0x200000)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects a zero counter`() {
        Dukpt.ksnIndex(0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects plaintext that is not block aligned`() {
        val ipek = Dukpt.generateIpek(ksnCounter1, bdk)
        Dukpt.encryptData("1234567".toByteArray(), ksnCounter1, ipek)
    }

    @Test
    fun `pads text to eight byte blocks`() {
        assertEquals(24, "9999990000123456=2812101".padToBlock().length)
        assertEquals(32, "9999990000123456=28121011".padToBlock().length)
        assertEquals(8, "abc".padToBlock().length)
        assertEquals(8, "abcdefgh".padToBlock().length)
    }

    private fun ByteArray.xorWith(hexMask: String): ByteArray {
        val mask = hexMask.hexToBytes()
        return ByteArray(size) { (this[it].toInt() xor mask[it].toInt()).toByte() }
    }
}

