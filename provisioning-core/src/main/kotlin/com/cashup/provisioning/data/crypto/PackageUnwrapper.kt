package com.cashup.provisioning.crypto

import android.util.Base64
import com.cashup.devicesdk.TerminalKeyMaterial
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.google.gson.Gson
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class PackageIntegrityException(message: String) : Exception(message)

internal data class PlainKeyPackage(
    val deviceId: String?,
    val keySetVersion: Int?,
    val algorithm: String?,
    val materials: Map<String, PlainKeyMaterial>?,
)

internal data class PlainKeyMaterial(val ipek: String?, val baseKsn: String?, val kcv: String?)

/** Verifies the server signature before decrypting any package material. */
open class PackageUnwrapper(private val rsa: RsaUnwrapper, private val gson: Gson = Gson()) {
    open fun unwrap(packageResponse: KeyPackageResponse): List<TerminalKeyMaterial> {
        if (packageResponse.algorithm != "TDES_DUKPT") throw PackageIntegrityException("Algoritma paket tidak didukung")
        fun decode(name: String, value: String?): ByteArray = try {
            Base64.decode(value ?: throw PackageIntegrityException("$name tidak ada"), Base64.DEFAULT)
        } catch (e: IllegalArgumentException) { throw PackageIntegrityException("$name bukan Base64") }


        val nonce = decode("nonce", packageResponse.nonce)
        val wrapped = decode("wrappedPackageKey", packageResponse.wrappedPackageKey)
        val ciphertext = decode("ciphertext", packageResponse.ciphertext)
        val signature = decode("packageSignature", packageResponse.packageSignature)
        val publicKey = decode("signingPublicKey", packageResponse.signingPublicKey)
        val aad = "${packageResponse.orderId}:${packageResponse.deviceId}:${packageResponse.keySetVersion}".toByteArray(Charsets.UTF_8)
        BcProvider.ensureInstalled()
        val verified = try {
            val key = KeyFactory.getInstance("Ed25519", BcProvider.NAME)
                .generatePublic(X509EncodedKeySpec(publicKey))
            Signature.getInstance("Ed25519", BcProvider.NAME).run {
                initVerify(key)
                update(aad + nonce + wrapped + ciphertext)
                verify(signature)
            }
        } catch (e: Exception) { false }
        if (!verified) throw PackageIntegrityException("PACKAGE_SIGNATURE_INVALID")

        val aesKey = try { rsa.unwrap(wrapped) }
        catch (e: Exception) { throw PackageIntegrityException("Gagal membuka package key: ${e.message}") }
        val plaintext = try {
            if (aesKey.size != 32) throw PackageIntegrityException("Panjang AES key tidak sah")
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(ciphertext)
            }
        } catch (e: Exception) { throw PackageIntegrityException("Gagal membuka paket key: ${e.message}") }
        finally { aesKey.fill(0) }

        val parsed = try { gson.fromJson(plaintext.decodeToString(), PlainKeyPackage::class.java) }
        catch (e: Exception) { throw PackageIntegrityException("JSON paket key tidak sah") }
        finally { plaintext.fill(0) }
        if (parsed?.deviceId != packageResponse.deviceId || parsed.keySetVersion != packageResponse.keySetVersion ||
            parsed.algorithm != "TDES_DUKPT") throw PackageIntegrityException("Binding plaintext paket tidak cocok")
        val source = parsed.materials ?: throw PackageIntegrityException("Material paket kosong")
        val result = mutableListOf<TerminalKeyMaterial>()
        try {
            for ((purpose, material) in source) {
                val ipek = decode("$purpose.ipek", material.ipek)
                var ksn: ByteArray? = null
                try {
                    ksn = decode("$purpose.baseKsn", material.baseKsn)
                    val expectedKcv = material.kcv ?: throw PackageIntegrityException("$purpose.kcv tidak ada")
                    if (!keyCheckValue(ipek.copyOf()).equals(expectedKcv, ignoreCase = true)) {
                        throw PackageIntegrityException("KCV tidak cocok untuk $purpose")
                    }
                    result += TerminalKeyMaterial(purpose, ipek, ksn)
                } catch (failure: Exception) {
                    ipek.fill(0)
                    ksn?.fill(0)
                    throw failure
                }
            }
            return result
        } catch (e: Exception) {
            result.forEach { it.zeroize() }
            throw e
        }
    }
}
