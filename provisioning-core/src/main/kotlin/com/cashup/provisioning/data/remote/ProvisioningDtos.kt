package com.cashup.provisioning.data.remote

/**
 * Bentuk body mengikuti diagram provisioning tim (`Provisioning.drawio`), yang
 * berwenang atas ini; nama path mengikuti `corepayment`. Di mana keduanya
 * berbeda, spec §1 menetapkan siapa yang menang, dan §8 mencatat apa yang masih
 * perlu dikonfirmasi backend.
 */

/**
 * Satu-satunya request yang TIDAK ditandatangani: backend belum mengenal public
 * key device, karena dua kunci itu justru baru dikirim di sini.
 *
 * [rsaPublicKey] adalah SPKI X.509 Base64, dipakai backend untuk membungkus
 * paket key. [eddsaPublicKey] adalah Ed25519 raw 32 byte Base64, dipakai backend
 * untuk memverifikasi tanda tangan setiap request sesudah ini.
 */
data class QrRedeemRequest(
    val challengeCode: String,
    val serialNumber: String,
    val rsaPublicKey: String,
    val eddsaPublicKey: String,
)

data class QrRedeemResponse(
    val orderId: String,
    val activationToken: String,
)

/**
 * `orderId` muncul di path DAN di body — konsekuensi dari memakai path
 * `corepayment` dengan body diagram tim. Tercatat di spec §8 item 5 sebagai
 * hal yang perlu dikonfirmasi backend.
 */
data class KeyPackageRequest(
    val orderId: String,
    val activationToken: String,
)

/**
 * [wrappedPackageKey] membawa material DUKPT terbungkus RSA.
 *
 * Bentuknya belum dikonfirmasi backend (spec §8 item 1): RSA-2048 OAEP-SHA256
 * hanya memuat 190 byte, jadi kalau paketnya JSON+base64 untuk empat pasang
 * IPEK/KSN, backend harus memakai skema hibrida seperti `edc-mobile` dan DTO ini
 * bertambah field. Kalau itu terjadi, yang berubah hanya berkas ini dan
 * `PackageUnwrapper` di Task 8.
 *
 * [appEddsaPublicKey] adalah gema public key yang dikirim saat redeem, plain.
 */
data class KeyPackageResponse(
    val orderId: String,
    val wrappedPackageKey: String,
    val appEddsaPublicKey: String? = null,
)

/**
 * [keyCheckValues] dikunci per nama purpose yang datang di paket — tidak ada
 * daftar purpose yang di-hardcode di mana pun (spec §7.2).
 */
data class ActivateRequest(
    val activationToken: String,
    val keyCheckValues: Map<String, String>,
)

data class ActivateResponse(
    val status: String,
)