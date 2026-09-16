package com.cashup.provisioning.domain

import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.ProvisioningState

/**
 * Keypair milik device, tanpa menyebut Android Keystore.
 *
 * [ensureRsaKeyPair] dan [ensureEd25519KeyPair] idempoten: memanggil ulang tidak
 * membuat key baru. Itu penting karena public key yang sudah didaftarkan ke
 * backend harus tetap milik key yang sama.
 */
interface ProvisioningKeys {
    fun ensureRsaKeyPair(): RsaKeyInfo
    /** Public key Ed25519 raw 32 byte, Base64. */
    fun ensureEd25519KeyPair(): String
    fun unwrapper(): RsaUnwrapper
    fun clearAll()
}

interface ProvisioningStateRepository {
    fun current(): ProvisioningState?
    fun save(state: ProvisioningState)
    fun clear()
}