package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PackageUnwrapperTest {

    private val ipek = ByteArray(16) { (it + 1).toByte() }
    private val ksn = ByteArray(10) { (it + 100).toByte() }

    private fun plaintextJson(kcvOverride: String? = null): String {
        val kcv = kcvOverride ?: keyCheckValue(ipek.copyOf())
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        return """
            {"materials":{
              "PIN":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"},
              "TRACK":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}
            }}
        """.trimIndent()
    }

    private fun unwrapperReturning(json: String) =
        PackageUnwrapper(RsaUnwrapper { json.toByteArray() })

    private fun wrappedInput() = Base64.getEncoder().encodeToString("ignored".toByteArray())

    @Test
    fun `returns one material per purpose in the package`() {
        val materials = unwrapperReturning(plaintextJson()).unwrap(wrappedInput())

        assertEquals(setOf("PIN", "TRACK"), materials.map { it.purpose }.toSet())
        assertTrue(materials.all { it.ipek.size == 16 })
        assertTrue(materials.all { it.ksn.size == 10 })
    }

    @Test
    fun `purposes are not hard-coded -- an unfamiliar purpose still comes through`() {
        val ipekB64 = Base64.getEncoder().encodeToString(ipek)
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        val kcv = keyCheckValue(ipek.copyOf())
        val json = """{"materials":{"SOMETHING_NEW":{"ipek":"$ipekB64","ksn":"$ksnB64","kcv":"$kcv"}}}"""

        val materials = unwrapperReturning(json).unwrap(wrappedInput())

        assertEquals(listOf("SOMETHING_NEW"), materials.map { it.purpose })
    }

    @Test
    fun `a mismatched KCV rejects the whole package`() {
        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning(plaintextJson(kcvOverride = "000000")).unwrap(wrappedInput())
        }

        assertTrue(failure.message!!.contains("KCV"))
    }

    @Test
    fun `a package with no materials is rejected rather than silently installing nothing`() {
        assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning("""{"materials":{}}""").unwrap(wrappedInput())
        }
    }

    @Test
    fun `plaintext that is not the expected JSON is rejected`() {
        assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning("not json at all").unwrap(wrappedInput())
        }
    }
}