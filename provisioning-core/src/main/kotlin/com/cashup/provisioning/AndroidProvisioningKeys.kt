package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyStore
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.domain.ProvisioningKeys
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.devicesdk.TerminalKeyMaterial
import android.util.Base64
import com.cashup.provisioning.crypto.Tr34KeyTokenParser
import com.cashup.provisioning.crypto.Tr34ParsingException

class AndroidProvisioningKeys(
    private val rsa: RsaKeyStore,
    private val ed25519: Ed25519KeyStore,
) : ProvisioningKeys {

    override fun ensureRsaKeyPair(): RsaKeyInfo = rsa.ensureKeyPair()

    override fun ensureEd25519KeyPair(): String = ed25519.ensureKeyPair()

    override fun unwrapper(): RsaUnwrapper = rsa.unwrapper()

    override fun unwrapTr34(packageResponse: KeyPackageResponse): List<TerminalKeyMaterial> {
        val materials = packageResponse.tr34Materials ?: error("TR34 materials tidak ada")
        val chain = packageResponse.kdhCertificateChain?.map { Base64.decode(it, Base64.DEFAULT) }
            ?: error("KDH certificate chain tidak ada")
        val unwrapped = mutableListOf<TerminalKeyMaterial>()
        try {
            materials.forEach { (purpose, material) ->
                val result = try {
                    Tr34KeyTokenParser.parseKeyToken(
                        Base64.decode(material.keyBlock, Base64.DEFAULT), chain, rsa::unwrapTr34EphemeralKey,
                    )
                } catch (failure: Tr34ParsingException) {
                    throw Tr34ParsingException("Key token TR-34 $purpose tidak valid: ${failure.message}")
                }
                val ksn = Base64.decode(material.baseKsn, Base64.DEFAULT)
                unwrapped += TerminalKeyMaterial(purpose, result.ipek, ksn)
            }
            return unwrapped
        } catch (failure: Exception) {
            unwrapped.forEach { it.zeroize() }
            throw failure
        }
    }

    override fun krdCsr(): String = rsa.krdCsr()

    override fun signActivation(bytes: ByteArray): ByteArray = rsa.sign(bytes)

    override fun clearAll() {
        rsa.clear()
        ed25519.clear()
    }
}
