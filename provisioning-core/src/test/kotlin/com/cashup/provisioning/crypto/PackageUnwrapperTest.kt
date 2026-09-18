package com.cashup.provisioning.crypto

import com.cashup.provisioning.data.remote.KeyPackageResponse
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PackageUnwrapperTest {
    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    @Test fun `signature is checked before RSA unwrap and AES decrypt`() {
        var rsaCalls = 0
        val response = KeyPackageResponse("order", "device", 1, "TDES_DUKPT", "challenge",
            wrappedPackageKey = b64(ByteArray(32)), nonce = b64(ByteArray(12)),
            ciphertext = b64(ByteArray(32)), signingPublicKey = b64(ByteArray(44)),
            packageSignature = b64(ByteArray(64)))
        val failure = runCatching { PackageUnwrapper(RsaUnwrapper { rsaCalls++; it }).unwrap(response) }.exceptionOrNull()
        assertEquals("PACKAGE_SIGNATURE_INVALID", failure?.message)
        assertEquals(0, rsaCalls)
    }

    @Test fun `valid signed hybrid package yields checked DUKPT materials`() {
        BcProvider.ensureInstalled()
        val signing = KeyPairGenerator.getInstance("Ed25519", BcProvider.NAME).generateKeyPair()
        val aesKey = ByteArray(32) { 9 }
        val nonce = ByteArray(12) { 4 }
        val wrapped = ByteArray(32) { 5 }
        val ipek = ByteArray(16) { 3 }
        val kcv = keyCheckValue(ipek.copyOf())
        val material = """{"ipek":"${b64(ipek)}","baseKsn":"${b64(ByteArray(10) { 2 })}","kcv":"$kcv"}"""
        val plaintext = """{"deviceId":"device","keySetVersion":1,"algorithm":"TDES_DUKPT","materials":{"TRACK":$material,"AMOUNT":$material,"PIN":$material}}"""
        val aad = "order:device:1".toByteArray()
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(aad)
            doFinal(plaintext.toByteArray())
        }
        val signature = Signature.getInstance("Ed25519", BcProvider.NAME).run {
            initSign(signing.private)
            update(aad + nonce + wrapped + ciphertext)
            sign()
        }
        val response = KeyPackageResponse("order", "device", 1, "TDES_DUKPT", "challenge",
            b64(wrapped), b64(nonce), b64(ciphertext), b64(signature), b64(signing.public.encoded))
        val materials = PackageUnwrapper(RsaUnwrapper { aesKey.copyOf() }).unwrap(response)
        assertEquals(setOf("TRACK", "AMOUNT", "PIN"), materials.map { it.purpose }.toSet())
        assertTrue(materials.all { it.ipek.contentEquals(ipek) })
        materials.forEach { it.zeroize() }
    }
}
