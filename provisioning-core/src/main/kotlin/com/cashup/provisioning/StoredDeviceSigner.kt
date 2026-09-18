package com.cashup.provisioning

import com.cashup.provisioning.crypto.Ed25519KeyStore
import com.cashup.provisioning.data.local.ProvisioningStateStore
import com.cashup.provisioning.domain.DeviceIdentitySink
import com.cashup.provisioning.domain.ProvisioningStateRepository
import com.cashup.signing.DeviceSigner

/**
 * Menyambungkan penyimpanan ke kontrak [DeviceSigner] milik `signing-core`.
 *
 * [deviceId] punya DUA sumber, dan urutannya penting:
 *
 * 1. [inFlightSerialNumber] — diisi `ProvisionDeviceUseCase` lewat
 *    [DeviceIdentitySink] segera setelah nomor seri terdeteksi (langkah 1),
 *    sebelum request bertanda tangan mana pun dikirim.
 * 2. State tersimpan — berlaku untuk seluruh lalu lintas setelah provisioning
 *    selesai.
 *
 * Sumber pertama bukan kenyamanan, melainkan syarat agar J1 bisa selesai sama
 * sekali: `stateStore` baru terisi di langkah TERAKHIR (`state.save`), padahal
 * `/package` dan `/activate` sudah harus ditandatangani jauh sebelum itu. Tanpa
 * itu, [deviceId] null sepanjang ceremony dan `SigningInterceptor` melempar
 * `DeviceNotProvisionedException` pada setiap percobaan provisioning device
 * bersih. Per spec §3.3 `X-Device-Id` = nomor seri, dan nomor seri sudah
 * diketahui sejak langkah 1 — ia tidak perlu menunggu konfirmasi backend.
 *
 * Di luar dua sumber itu [deviceId] tetap null, dan itu tetap perilaku yang
 * diinginkan: request bertanda tangan tidak punya urusan berjalan pada device
 * yang belum dikenal backend.
 */
class StoredDeviceSigner(
    private val state: ProvisioningStateRepository,
    private val signBytes: (ByteArray) -> ByteArray,
    private val currentPublicKey: (() -> String)?,
) : DeviceSigner, DeviceIdentitySink {

    constructor(state: ProvisioningStateRepository, signBytes: (ByteArray) -> ByteArray) :
        this(state, signBytes, null)

    /**
     * Konstruktor produksi. Dipisah dari yang utama supaya kelas ini bisa
     * dirakit di tes JVM biasa tanpa menyeret Android Keystore lewat
     * [Ed25519KeyStore].
     */
    constructor(stateStore: ProvisioningStateStore, ed25519: Ed25519KeyStore) :
            this(stateStore, ed25519::sign, ed25519::ensureKeyPair)

    @Volatile
    private var inFlightSerialNumber: String? = null

    override fun setInFlightSerial(serial: String?) {
        inFlightSerialNumber = serial
    }

    override fun deviceId(): String? {
        val identity = state.identity() ?: return null
        val current = currentPublicKey ?: return identity.deviceId
        // A rotated Ed25519 key needs a new QR redeem before signed traffic.
        return identity.deviceId.takeIf { identity.devicePublicKey == current() }
    }

    override fun sign(canonicalBytes: ByteArray): ByteArray = signBytes(canonicalBytes)
}
