package com.cashup.provisioning.crypto

import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OaepSha256Test {
    private val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val modulusBytes = 256

    @Test
    fun `decodes OAEP SHA256 MGF1 SHA256 after raw RSA private operation`() {
        val secret = ByteArray(24) { it.toByte() }
        val wrapped = wrap(secret, MGF1ParameterSpec.SHA256)
        val raw = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, pair.private)
            doFinal(wrapped)
        }

        assertArrayEquals(secret, OaepSha256.decode(raw, modulusBytes))
    }

    @Test
    fun `rejects the wrong MGF1 digest and malformed OAEP`() {
        val wrapped = wrap(ByteArray(16) { 7 }, MGF1ParameterSpec.SHA1)
        val wrongDigestBlock = Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.DECRYPT_MODE, pair.private)
            doFinal(wrapped)
        }
        assertThrows(BadPaddingException::class.java) {
            OaepSha256.decode(wrongDigestBlock, modulusBytes)
        }
        assertThrows(BadPaddingException::class.java) {
            OaepSha256.decode(ByteArray(modulusBytes), modulusBytes)
        }
    }

    private fun wrap(secret: ByteArray, mgf1: MGF1ParameterSpec): ByteArray {
        BcProvider.ensureInstalled()
        return Cipher.getInstance("RSA/ECB/OAEPPadding", BcProvider.NAME).run {
            init(Cipher.ENCRYPT_MODE, pair.public,
                OAEPParameterSpec("SHA-256", "MGF1", mgf1, PSource.PSpecified.DEFAULT))
            doFinal(secret)
        }
    }
}
