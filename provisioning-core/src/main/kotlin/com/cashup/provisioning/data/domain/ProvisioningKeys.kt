package com.cashup.provisioning.domain

import com.cashup.provisioning.crypto.RsaKeyInfo
import com.cashup.provisioning.crypto.RsaUnwrapper
import com.cashup.provisioning.data.local.DukptState
import com.cashup.provisioning.data.local.IdentityState
import com.cashup.provisioning.data.remote.KeyPackageResponse
import com.cashup.devicesdk.TerminalKeyMaterial

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
    fun unwrapTr34(packageResponse: KeyPackageResponse): List<TerminalKeyMaterial> =
        error("TR34_2019 belum didukung oleh key adapter")
    fun certificateChain(): List<String> = emptyList()
    fun krdCsr(): String = certificateChain().firstOrNull().orEmpty()
    fun signActivation(bytes: ByteArray): ByteArray
    fun clearAll()
}

/**
 * Dua record independen — spec 17 September §5. Identity persist segera
 * setelah redeem sukses; DUKPT persist terpisah setelah activate sukses.
 * Rollback DUKPT (spec §2.1) memanggil HANYA `clearDukpt()`, tidak
 * `clearIdentity()`.
 */
interface ProvisioningStateRepository {
    fun identity(): IdentityState?
    fun saveIdentity(state: IdentityState)
    fun clearIdentity()

    fun dukpt(): DukptState?
    fun saveDukpt(state: DukptState)
    fun clearDukpt()
}
