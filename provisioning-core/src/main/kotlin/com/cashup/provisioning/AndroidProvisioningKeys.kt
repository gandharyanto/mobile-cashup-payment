package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaKeyStore
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.domain.ProvisioningKeys

class AndroidProvisioningKeys(
    private val rsa: RsaKeyStore,
    private val ed25519: Ed25519KeyStore,
) : ProvisioningKeys {

    override fun ensureRsaKeyPair(): RsaKeyInfo = rsa.ensureKeyPair()

    override fun ensureEd25519KeyPair(): String = ed25519.ensureKeyPair()

    override fun unwrapper(): RsaUnwrapper = rsa.unwrapper()

    override fun clearAll() {
        rsa.clear()
        ed25519.clear()
    }
}