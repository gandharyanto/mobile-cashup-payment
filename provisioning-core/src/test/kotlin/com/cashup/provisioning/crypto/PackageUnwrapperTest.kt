package com.cashup.provisioning.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64

/**
 * [PackageUnwrapper] memanggil `android.util.Base64` (bukan `java.util.Base64`,
 * yang baru ada di API 26 -- lihat KDoc di [PackageUnwrapper]), jadi test ini
 * perlu Robolectric supaya `android.util.Base64` benar-benar berfungsi, bukan
 * stub yang mengembalikan null.
 */
@RunWith(RobolectricTestRunner::class)
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

    /**
     * Gson membangun [PlainKeyMaterial] lewat `Unsafe`, jadi field yang hilang
     * tetap masuk sebagai `null` betapapun tipe Kotlin-nya non-null. Yang harus
     * keluar dari sini adalah kegagalan integritas yang menyebut field-nya,
     * bukan NullPointerException tanpa konteks.
     */
    @Test
    fun `a material missing a field is named, not turned into a null pointer`() {
        val ksnB64 = Base64.getEncoder().encodeToString(ksn)
        val json = """{"materials":{"PIN":{"ksn":"$ksnB64","kcv":"ABCDEF"}}}"""

        val failure = assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning(json).unwrap(wrappedInput())
        }

        assertTrue(failure.message, failure.message!!.contains("ipek"))
        assertTrue(failure.message, failure.message!!.contains("PIN"))
    }

    @Test
    fun `plaintext that is not the expected JSON is rejected`() {
        assertThrows(PackageIntegrityException::class.java) {
            unwrapperReturning("not json at all").unwrap(wrappedInput())
        }
    }
}