package com.cashup.provisioning.data.remote

/**
 * Bentuk body mengikuti `edc-mobile` (`D:\gandha_cashup\projects\edc-mobile copy`)
 * penuh — spec 17 September §1–§3. Nama path tetap mengikuti `corepayment`,
 * sama seperti spec sebelumnya; yang berubah di sini murni bentuk body/method.
 */

/**
 * Satu-satunya request yang TIDAK ditandatangani: backend belum mengenal public
 * key device, karena dua kunci itu justru baru dikirim di sini.
 *
 * [rsaPublicKey] SPKI X.509 Base64 — dipakai backend membungkus paket key DAN
 * memverifikasi [krdCsr]. [eddsaPublicKey] Ed25519 raw 32 byte
 * Base64 — dipakai backend memverifikasi tanda tangan setiap request sesudah
 * ini. [purposes] SELALU tiga tetap (spec §1) — client yang menyatakan apa
 * yang diminta, bukan menunggu server memberi tahu. [krdCsr] adalah PKCS#10
 * DER Base64 yang ditandatangani private key RSA device; backend menerbitkan
 * sertifikat KRD dari CSR tersebut.
 */
data class QrRedeemRequest(
    val qrToken: String,
    val serialNumber: String,
    val rsaPublicKey: String,
    val devicePublicKey: String,
    val purposes: Set<String>,
    val krdCsr: String,
) {
    /** Compatibility view for older tests/callers; not serialized as a field. */
    val deviceCertificateChain: List<String> get() = listOf(krdCsr)

    constructor(
        qrToken: String,
        serialNumber: String,
        rsaPublicKey: String,
        devicePublicKey: String,
        purposes: Set<String>,
        legacyCertificateChain: List<String>,
    ) : this(qrToken, serialNumber, rsaPublicKey, devicePublicKey, purposes, legacyCertificateChain.firstOrNull().orEmpty())
}

/**
 * [deviceId] (UUID) sejak response ini jadi `X-Device-Id` — PERSIST segera,
 * spec §2 langkah 5, jangan tunggu DUKPT selesai.
 *
 * [dukptProvisioningRequired] `false` berarti device sudah punya DUKPT ACTIVE
 * dan redeem ini cuma me-refresh credential Ed25519 — [orderId]/
 * [activationToken]/[keySetVersion] semuanya `null` dalam kasus itu, ceremony
 * berhenti di sini (spec §2 langkah 6a).
 */
data class QrRedeemResponse(
    val challengeId: String? = null,
    val deviceId: String,
    val credentialKeyVersion: Int,
    val dukptProvisioningRequired: Boolean,
    val orderId: String? = null,
    val activationToken: String? = null,
    val keySetVersion: Int? = null,
    val expiresAt: String? = null,
    val status: String,
    val certificateChain: List<String>? = null,
)

/**
 * [algorithm] WAJIB `"TDES_DUKPT"` — nilai lain ditolak eksplisit (spec §2
 * langkah 6b, `edc-mobile` juga menolak `TR34_2019`).
 *
 * [wrappedPackageKey] membungkus kunci AES-256 (32 byte) via RSA-OAEP, BUKAN
 * seluruh payload DUKPT lagi — payload sesungguhnya ada di [ciphertext],
 * AES-GCM dengan [nonce]. [packageSignature] tanda tangan Ed25519 server atas
 * AAD+nonce+wrappedPackageKey+ciphertext, diverifikasi pakai [signingPublicKey]
 * SEBELUM apa pun di atas dibuka — lihat `PackageUnwrapper.kt`.
 */
data class KeyPackageResponse(
    val orderId: String,
    val deviceId: String,
    val keySetVersion: Int,
    val algorithm: String,
    val activationChallenge: String,
    val wrappedPackageKey: String? = null,
    val nonce: String? = null,
    val ciphertext: String? = null,
    val packageSignature: String? = null,
    val signingPublicKey: String? = null,
    val tr34Materials: Map<String, Tr34MaterialResponse>? = null,
    val kdhCertificateChain: List<String>? = null,
)

data class Tr34MaterialResponse(val keyBlock: String, val baseKsn: String, val kcv: String)

/** Plaintext hasil decrypt AES-GCM — TIDAK pernah dikirim/diterima langsung dari backend. */

/**
 * Ketiga field sengaja nullable meski paket yang sah selalu memuatnya — lihat
 * KDoc `PackageUnwrapper` untuk alasannya (Gson lewat `Unsafe`, null-safety
 * Kotlin tidak berjalan). TIDAK berubah dari kontrak lama.
 */

data class KeyPackageRequest(val orderId: String, val activationToken: String)

/**
 * [keyCheckValues] dikunci per tiga purpose tetap (TRACK/AMOUNT/PIN).
 * [deviceSignature] RSA `SHA256withRSA` atas
 * `"{orderId}:{keySetVersion}:{activationChallenge}"`, Base64 — proof-of-
 * possession kedua, memakai key RSA identity yang sama dengan yang membuka
 * paket. Lihat `ProvisionDeviceUseCase.kt`.
 */
data class ActivateRequest(
    val activationToken: String,
    val keyCheckValues: Map<String, String>,
    val deviceSignature: String,
)

data class ActivateResponse(
    val orderId: String,
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val status: String,
)
