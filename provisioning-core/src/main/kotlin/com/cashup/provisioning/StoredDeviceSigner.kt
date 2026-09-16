package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.signing.DeviceSigner

/**
 * Menyambungkan penyimpanan ke kontrak [DeviceSigner] milik `signing-core`.
 *
 * [deviceId] baru punya nilai setelah provisioning berhasil — sebelum itu
 * `SigningInterceptor` melempar `DeviceNotProvisionedException`, yang memang
 * yang diinginkan: request bertanda tangan tidak punya urusan berjalan sebelum
 * backend mengenal device ini.
 */
class StoredDeviceSigner(
    private val stateStore: ProvisioningStateStore,
    private val ed25519: Ed25519KeyStore,
) : DeviceSigner {

    override fun deviceId(): String? = stateStore.serialNumber()

    override fun sign(canonicalBytes: ByteArray): ByteArray = ed25519.sign(canonicalBytes)
}