package com.cashup.devicesdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TerminalKeyMaterialTest {

    private fun material() = TerminalKeyMaterial(
        purpose = "PIN",
        ipek = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D),
        ksn = byteArrayOf(0x4E, 0x5F),
    )

    @Test
    fun `toString names the purpose but never the key bytes`() {
        val rendered = material().toString()

        assertEquals("TerminalKeyMaterial(purpose=PIN, ipek=<redacted>, ksn=<redacted>)", rendered)
        assertFalse(rendered.contains("0a", ignoreCase = true), "ipek byte leaked into toString")
        assertFalse(rendered.contains("4e", ignoreCase = true), "ksn byte leaked into toString")
    }

    @Test
    fun `zeroize clears both arrays in place`() {
        val subject = material()
        val ipekRef = subject.ipek
        val ksnRef = subject.ksn

        subject.zeroize()

        assertEquals(0, ipekRef.count { it != 0.toByte() }, "ipek still holds non-zero bytes")
        assertEquals(0, ksnRef.count { it != 0.toByte() }, "ksn still holds non-zero bytes")
    }
}
