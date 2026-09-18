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
        return materials.map { (purpose, material) ->
            val result = Tr34KeyTokenParser.parseKeyToken(
                Base64.decode(material.keyBlock, Base64.DEFAULT), chain, rsa.privateKeyHandle(),
            )
            val ksn = Base64.decode(material.baseKsn, Base64.DEFAULT)
            TerminalKeyMaterial(purpose, result.ipek, ksn)
        }
    }

    override fun krdCsr(): String = rsa.krdCsr()

    override fun signActivation(bytes: ByteArray): ByteArray = rsa.sign(bytes)

    override fun clearAll() {
        rsa.clear()
        ed25519.clear()
    }
}
