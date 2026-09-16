package com.cashup.provisioning.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceTest {

    private val ipek = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D, 0x4E, 0x5F, 0x60, 0x71)

    @Test
    fun `secret renders length and a short fingerprint, never the bytes`() {
        // Perilaku default -- revealSecrets mati.
        val rendered = Evidence.secret(ipek)

        assertTrue(rendered, rendered.startsWith("len=8 fp="))
        assertFalse("raw byte leaked", rendered.contains("0a1b", ignoreCase = true))
        assertEquals(8, rendered.substringAfter("fp=").length)
    }

    @Test
    fun `the same bytes always fingerprint the same way`() {
        assertEquals(Evidence.secret(ipek), Evidence.secret(ipek.copyOf()))
    }

    @Test
    fun `one changed byte changes the fingerprint`() {
        val other = ipek.copyOf().also { it[0] = 0x0B }

        assertNotEquals(Evidence.secret(ipek), Evidence.secret(other))
    }

    @Test
    fun `secret does not mutate or zero the caller's array`() {
        val original = ipek.copyOf()

        Evidence.secret(ipek)

        // Berbeda dari keyCheckValue, yang memang menol-kan. Jurnal tidak boleh
        // punya efek samping terhadap nilai yang sedang dipakai alur utama.
        assertTrue(original.contentEquals(ipek))
    }

    @Test
    fun `class-1 values are rendered in full`() {
        // Token sekali pakai, ciphertext, dan public key semuanya dibutuhkan utuh
        // saat mendebug alur, dan tidak satu pun rahasia.
        assertEquals("ABCD-1234-EFGH", Evidence.token("ABCD-1234-EFGH"))
        assertEquals("d3JhcHBlZA==", Evidence.ciphertext("d3JhcHBlZA=="))
        assertEquals("TUZrd0V3WUhLb1pJemowQ0FR", Evidence.publicKey("TUZrd0V3WUhLb1pJemowQ0FR"))
    }

    @Test
    fun `revealSecrets appends the raw bytes when it is on`() {
        Evidence.revealSecrets = true
        try {
            val rendered = Evidence.secret(ipek)
            assertTrue(rendered, rendered.startsWith("len=8 fp="))
            assertTrue(rendered, rendered.endsWith(" raw=0a1b2c3d4e5f6071"))
        } finally {
            Evidence.revealSecrets = false
        }
    }

    @Test
    fun `revealSecrets defaults to off`() {
        // Uji coba berjalan di terminal sungguhan dengan key sungguhan. Default
        // yang salah di sini berarti key asli masuk logcat tanpa ada yang memilihnya.
        assertFalse(Evidence.revealSecrets)
        assertFalse(Evidence.secret(ipek).contains("raw="))
    }
}