# Provisioning — Adopsi Penuh Kontrak `edc-mobile` — Design Spec

**Tanggal:** 2026-09-17
**Status:** disetujui, siap dijadikan implementation plan
**Menggantikan:** `docs/superpowers/specs/2026-09-16-provisioning-design.md` §1–§4, §7 (kontrak HTTP, kriptografi, ceremony, state model). Bagian yang **tidak** disentuh dokumen ini (§5 struktur module secara umum, §6 MVVM, §9 daftar konfirmasi lama, §10 di luar scope) tetap berlaku kecuali disebutkan berbeda di sini.

Provisioning J1 sudah selesai diimplementasikan, direview habis-habisan, dan di-merge ke `main` mengikuti spec 16 September (diagram tim sebagai otoritas tertinggi, `edc-mobile` cuma pengisi celah). Dokumen ini membalik urutan itu: **`edc-mobile` sekarang otoritas penuh** untuk bentuk kontrak wire provisioning. Alasannya eksplisit dari percakapan yang membentuk dokumen ini: diagram tim ternyata cuma sketsa konseptual timnya sendiri, sementara `edc-mobile` (`D:\gandha_cashup\projects\edc-mobile copy`) adalah implementasi yang **sudah terbukti jalan** lawan `corepayment` sungguhan.

---

## 1. Apa yang berubah, ringkas

| Aspek | Spec 16 Sep (sekarang di `main`) | Spec ini |
|---|---|---|
| Otoritas bentuk body | Diagram tim | **`edc-mobile`, penuh** |
| Identitas device (`X-Device-Id`) | `serialNumber` lokal | **`deviceId` UUID terbitan backend**, didapat dari response `qr-redeem` |
| Purpose DUKPT | Data-driven (ikut apa pun yang backend kirim) | **3 tetap, sisi client**: `TRACK`, `AMOUNT`, `PIN`, dikirim di request |
| `/package` | `POST` + body `{orderId, activationToken}` | **`GET`** + header `X-Activation-Token` |
| Bentuk paket key | RSA-OAEP langsung membungkus seluruh payload | **Hybrid**: RSA-OAEP membungkus kunci AES-256, payload di AES-GCM, ditandatangani Ed25519 oleh server — **wajib diverifikasi sebelum decrypt** |
| Proof-of-possession | Tidak ada | **Sertifikat self-signed X.509** atas RSA key, dikirim sebagai `deviceCertificateChain` |
| Model atomicity | All-or-nothing, satu titik persist di akhir | **Dua fase**: identity (deviceId+credentialKeyVersion) persist segera setelah redeem sukses; DUKPT persist terpisah setelah activate sukses. Rollback DUKPT tidak menyentuh identity. |
| Scope J2 | Eksplisit di luar scope | **Sebagian masuk** sebagai efek samping: redeem oleh device yang sudah aktif DUKPT-nya memicu jalur refresh-identitas-saja (`dukptProvisioningRequired=false`), tanpa menu/UI J2 penuh |
| RSA key purpose | `PURPOSE_DECRYPT` saja | **`PURPOSE_DECRYPT or PURPOSE_SIGN`** — dipakai juga untuk `deviceSignature` proof-of-possession di `/activate` dan menandatangani sertifikat sendiri |

Yang **tidak** berubah: path tiga endpoint (`v1/terminal-key-provisioning/qr-redeem`, `.../orders/{orderId}/package`, `.../orders/{orderId}/activate` — sudah cocok dengan `corepayment`/`edc-mobile` sejak spec sebelumnya), algoritma signing request (Ed25519, canonical string, header `X-Timestamp`/`X-Nonce`/`X-Signature`), keputusan TEE-vs-software untuk RSA (§4.5 spec lama, `RsaKeyLocationPolicy`), logika slot vendor tunggal (§4.3 spec lama), `Evidence`/`ProvisioningJournal` (sementara, debug-only), dan empat layar UI (Gate/Scan/Processing/Result — cuma Result butuh teks tambahan untuk kasus refresh-identitas).

---

## 2. Ceremony baru

```
 1  Generate/load RSA identity keypair (PURPOSE_DECRYPT|PURPOSE_SIGN, TEE kalau
    bisa — kebijakan §4.5 spec lama tidak berubah) + sertifikat self-signed X.509
 2  Generate/load Ed25519 signing keypair (tidak berubah dari spec lama)
 3  Scan QR → challengeCode

 4  POST v1/terminal-key-provisioning/qr-redeem     [TANPA signature]
     →  { challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey,
          purposes: ["TRACK","AMOUNT","PIN"], deviceCertificateChain }
     ←  { deviceId, credentialKeyVersion, dukptProvisioningRequired,
          orderId?, activationToken?, keySetVersion?, expiresAt?, status }

 5  PERSIST deviceId + credentialKeyVersion SEKARANG — sebelum langkah apa pun
    lagi. X-Device-Id sejak titik ini = deviceId (UUID backend), BUKAN
    serialNumber (§8 spec lama, keputusan itu dibatalkan).

 6a dukptProvisioningRequired = false
    → SELESAI. Refresh identitas saja; DUKPT key lama (kalau ada) tidak disentuh.

 6b dukptProvisioningRequired = true
    → orderId, activationToken, keySetVersion WAJIB ada di response (kalau
      tidak, PROTOCOL_VIOLATION — backend melanggar kontraknya sendiri)

    GET v1/terminal-key-provisioning/orders/{orderId}/package  [signed]
        Header: X-Activation-Token: <activationToken>
     ←  { orderId, deviceId, keySetVersion, algorithm, activationChallenge,
          wrappedPackageKey, nonce, ciphertext, packageSignature,
          signingPublicKey }

    → TOLAK kalau algorithm != "TDES_DUKPT" (TR34_2019 di luar scope, sama
      seperti edc-mobile)
    → TOLAK kalau encrypted.deviceId != deviceId dari langkah 4/5 (paket
      diterbitkan untuk device lain)

    → Verifikasi packageSignature:
        signed  = AAD + nonce + wrappedPackageKey + ciphertext (raw bytes,
                  hasil decode base64)
        AAD     = "{orderId}:{deviceId}:{keySetVersion}".toByteArray()
        Ed25519.verify(signed, packageSignature, signingPublicKey)
        → gagal verifikasi = PACKAGE_SIGNATURE_INVALID, seluruh ceremony
          batal (rollback DUKPT, identity TETAP tersimpan)

    → RSA-OAEP buka wrappedPackageKey dengan RSA identity private key →
      kunci AES-256 (32 byte — muat jauh di bawah batas 190 byte RSA-2048-
      OAEP, soal kapasitas di §8 spec lama sudah tidak relevan)
    → AES-GCM decrypt ciphertext (key=kunci AES di atas, nonce, AAD sama
      dengan di atas) → plaintext JSON:
        { deviceId, keySetVersion, algorithm,
          materials: { TRACK: {ipek,baseKsn,kcv}, AMOUNT: {...}, PIN: {...} } }
    → Verifikasi plaintext.deviceId == encrypted.deviceId &&
      plaintext.keySetVersion == encrypted.keySetVersion
    → Verifikasi plaintext.algorithm == "TDES_DUKPT"

    → Hitung KCV tiap purpose (TRACK/AMOUNT/PIN — 3 tetap, bukan data-driven
      lagi), verifikasi terhadap material.kcv
    → Install: 1 purpose dapat slot modul aman vendor, 2 sisanya ke vault TEE
      (§4.3 spec lama TIDAK berubah — vendor cuma punya 1 slot DUKPT)

    → deviceSignature = RSA-sign("{orderId}:{keySetVersion}:{activationChallenge}",
                                  SHA256withRSA, RSA identity private key)

    POST v1/terminal-key-provisioning/orders/{orderId}/activate  [signed]
     →  { activationToken, keyCheckValues: {TRACK:.., AMOUNT:.., PIN:..},
          deviceSignature }
     ←  { orderId, deviceId, keySetId, keySetVersion, status }

    → Verifikasi activated.keySetVersion == keySetVersion yang dijanjikan
      response redeem (langkah 4)
    → PERSIST DUKPT state (keySetId, keySetVersion, backing per purpose) —
      TERPISAH dari identity state yang sudah tersimpan di langkah 5

 7  SELESAI
```

### 2.1 Rollback dua tingkat

Prinsip atomic spec lama (§7.1: "gagal di langkah mana pun → semua key dihapus") **dibatalkan sebagian**. Sekarang:

- **Sebelum langkah 5** (redeem gagal/ditolak): rollback total — keypair RSA/Ed25519 dihapus, tidak ada state tersisa. Sama seperti sebelumnya.
- **Setelah langkah 5, sebelum langkah 7** (gagal di `/package` atau `/activate`, termasuk `PACKAGE_SIGNATURE_INVALID`/KCV mismatch/installer menolak): rollback **DUKPT saja** — `installer.wipe()`, DUKPT state dibuang. **Identity (deviceId, credentialKeyVersion, kedua keypair) TETAP tersimpan** — device sudah "punya identitas", tinggal butuh redeem ulang untuk mencoba DUKPT lagi, tidak perlu scan QR dari nol kalau `challengeCode` masih hidup.

Ini konsekuensi langsung dari keputusan "identitas & DUKPT dipisah" — device yang gagal di `/package` bukan device yang "tidak dikenal" bagi backend, dan tidak seharusnya diperlakukan begitu di sisi client juga.

---

## 3. DTO (ditulis ulang total)

```kotlin
/** Tidak ditandatangani — backend belum mengenal identitas device di titik ini. */
data class QrRedeemRequest(
    val challengeCode: String,
    val serialNumber: String,
    val rsaPublicKey: String,       // SPKI X.509, Base64
    val eddsaPublicKey: String,     // Ed25519 raw 32 byte, Base64
    val purposes: Set<String>,      // selalu {"TRACK","AMOUNT","PIN"}
    val deviceCertificateChain: List<String>,  // DER Base64, leaf dulu
)

data class QrRedeemResponse(
    val deviceId: String,                    // UUID — jadi X-Device-Id sejak sini
    val credentialKeyVersion: Int,
    val dukptProvisioningRequired: Boolean,
    val orderId: String? = null,             // null kalau dukptProvisioningRequired=false
    val activationToken: String? = null,
    val keySetVersion: Int? = null,
    val expiresAt: String? = null,
    val status: String,
)

data class KeyPackageResponse(
    val orderId: String,
    val deviceId: String,
    val keySetVersion: Int,
    val algorithm: String,           // WAJIB "TDES_DUKPT", tolak selainnya
    val activationChallenge: String,
    val wrappedPackageKey: String,   // RSA-OAEP wrapped AES-256 key, Base64
    val nonce: String,               // AES-GCM nonce, Base64
    val ciphertext: String,          // AES-GCM ciphertext, Base64
    val packageSignature: String,    // Ed25519 signature, Base64
    val signingPublicKey: String,    // Ed25519 public key server, Base64
)

/** Plaintext hasil decrypt AES-GCM — TIDAK pernah dikirim/diterima langsung. */
internal data class PlainKeyPackage(
    val deviceId: String,
    val keySetVersion: Int,
    val algorithm: String,
    val materials: Map<String, PlainKeyMaterial>,  // key = "TRACK"/"AMOUNT"/"PIN"
)
internal data class PlainKeyMaterial(val ipek: String?, val baseKsn: String?, val kcv: String?)

data class ActivateRequest(
    val activationToken: String,
    val keyCheckValues: Map<String, String>,  // {"TRACK": "A1B2C3", ...}
    val deviceSignature: String,               // RSA SHA256withRSA, Base64
)

data class ActivateResponse(
    val orderId: String,
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val status: String,
)
```

`ApiEnvelope`/`safeEnvelopeCall`/header signing (`X-Timestamp`, `X-Correlation-Id`, `X-Device-Id`, `X-Nonce`, `X-Signature`, canonical string) — **semua tidak berubah** dari spec lama §3.2/§3.3, kecuali nilai `X-Device-Id` sekarang `deviceId` UUID, bukan `serialNumber`.

---

## 4. Kriptografi

### 4.1 RSA dual-purpose

`RsaKeyStore` (jalur TEE, `KeyGenParameterSpec.Builder`):

```kotlin
KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_SIGN)
    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)  // SHA256: sign & OAEP digest; SHA1: MGF1 kalau backend pakai itu (§4.5 spec lama)
    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
    // ... StrongBox opportunistic, sertifikat, dst — tidak berubah dari §4.5 spec lama
```

Satu keypair, dua kegunaan: unwrap (`Cipher`, OAEP) dan tanda tangan (`Signature`, `SHA256withRSA`) — dipakai untuk `deviceSignature` proof-of-possession di `/activate` dan untuk menandatangani sertifikat sendiri. Kebijakan TEE-vs-software (`RsaKeyLocationPolicy`, MGF1 digest confirmation §8 item 1b spec lama) **tidak berubah** — cuma sekarang yang dibungkus RSA jauh lebih kecil (32 byte kunci AES, bukan seluruh payload DUKPT).

`java.security.Signature` bekerja normal di atas handle `PrivateKey` AndroidKeyStore non-extractable — tidak perlu private key mentah untuk menandatangani, baik untuk `deviceSignature` maupun untuk `JcaContentSignerBuilder` di bawah.

### 4.2 Sertifikat self-signed X.509

Port dari `DeviceIdentityStore.certificateChain()` di `edc-mobile`:

```kotlin
val subject = X500Name("CN=cashup-edc-device")
val builder = JcaX509v3CertificateBuilder(
    subject, BigInteger.valueOf(now.epochMilli()),
    Date.from(now.minusSeconds(60)), Date.from(now.plusSeconds(3650L * 86_400)),
    subject, rsaPublicKey,
)
val signer = JcaContentSignerBuilder("SHA256withRSA").build(rsaPrivateKeyHandle)
val certificate = JcaX509CertificateConverter().getCertificate(builder.build(signer)).encoded
```

Dibuat sekali (idempoten, seperti keypair-nya sendiri), disimpan sebagai DER Base64 di penyimpanan yang sama dengan `RsaKeyStore`. Dikirim di `deviceCertificateChain` (list beranggota satu — self-signed, tidak ada rantai).

### 4.3 Verifikasi signature paket

Ed25519, provider BouncyCastle eksplisit (sama seperti `Ed25519KeyStore`, alasan yang sama: `KEY_ALGORITHM_ED25519` tidak ada di Android). Signed bytes = `AAD + nonce + wrappedPackageKey + ciphertext`, semua raw byte hasil decode Base64, bukan string-nya. Gagal verifikasi → `PACKAGE_SIGNATURE_INVALID`, batalkan sebelum RSA-OAEP disentuh sama sekali (jangan buka apa pun dari paket yang tidak terverifikasi).

### 4.4 Unwrap hybrid

RSA-OAEP (kebijakan digest MGF1 sama seperti §4.5 spec lama) membuka kunci AES-256, lalu `AES/GCM/NoPadding` (128-bit tag, nonce dari response, AAD sama dengan yang diverifikasi di §4.3) mendekripsi payload JSON. `PackageUnwrapper` tetap membaca `materials` secara data-driven dari map (bertahan kalau backend mengirim purpose tak terduga), tapi **request**-nya sekarang deklaratif — cuma minta 3 purpose tetap.

---

## 5. State model

`ProvisioningStateStore` pecah jadi dua record independen:

```kotlin
data class IdentityState(
    val serialNumber: String,
    val deviceId: String,
    val credentialKeyVersion: Int,
)

data class DukptState(
    val deviceId: String,
    val keySetId: Long,
    val keySetVersion: Int,
    val backings: Map<String, String>,  // purpose -> KeyBacking.name
)
```

`ProvisioningStateRepository` (interface yang dikonsumsi `ProvisionDeviceUseCase`) dapat dua pasang metode: `identity(): IdentityState?` / `saveIdentity(...)` / `clearIdentity()`, dan `dukpt(): DukptState?` / `saveDukpt(...)` / `clearDukpt()`.

`StoredDeviceSigner.deviceId()` baca dari `identity()?.deviceId` — bukan lagi `serialNumber`. Mekanisme "in-flight serial" dari fix Critical-1 (spec lama) **digantikan** oleh persist identity yang genuinely lebih awal (langkah 5 ceremony) — tidak perlu lagi field volatile sementara, karena sekarang benar-benar tersimpan sebelum request bertanda tangan pertama.

`AppContainer`/`GateFragment`'s `isProvisioned()` sekarang berarti **DUKPT aktif** (`dukpt() != null`), bukan sekadar "ada state apa pun" — device yang cuma punya identity (habis refresh-identitas-saja) tetap dianggap belum "provisioned" untuk keperluan routing Home vs Scan QR, karena dia belum bisa transaksi (tidak ada DUKPT key).

---

## 6. Di luar scope (tidak berubah dari keputusan awal)

- **TR34_2019** — ditolak eksplisit, sama seperti `edc-mobile`.
- **J2 penuh** (menu Device Settings, re-provisioning UI, deactivation) — cuma efek samping `dukptProvisioningRequired=false` yang masuk, bukan journey UI lengkap.
- **`kdhCertificateChain`/`keyBlock`** (field TR34) — tidak dipakai.
- Semua item lain di §10 spec 16 September tetap di luar scope.

---

## 7. Konsekuensi terhadap kode yang sudah ada

Ini bukan tambahan, ini **penulisan ulang** modul-modul berikut (semuanya sudah ada di `main`, hasil plan 16 September):

| Berkas | Nasib |
|---|---|
| `ProvisioningDtos.kt` | Ditulis ulang total (§3) |
| `ProvisioningApi.kt` | `/package` jadi `@GET`+header |
| `ProvisioningRepository.kt` | Signature method berubah mengikuti DTO baru |
| `RsaKeyStore.kt` | Tambah `PURPOSE_SIGN`, `sign()`, `certificateChain()` |
| `PackageUnwrapper.kt` | Ditulis ulang — verifikasi signature dulu, baru hybrid decrypt |
| `ProvisioningStateStore.kt` | Pecah jadi `identity`/`dukpt`, dua titik persist |
| `StoredDeviceSigner.kt` | Baca `deviceId`, bukan `serialNumber` |
| `ProvisionDeviceUseCase.kt` | Percabangan `dukptProvisioningRequired`, rollback dua tingkat |
| `AndroidProvisioningKeys.kt` | Expose `certificateChain()`/`sign()` dari `RsaKeyStore` |
| `AppContainer.kt` | `isProvisioned()` baca `dukpt()`, bukan state generik |
| `ResultFragment.kt` | Teks tambahan untuk kasus refresh-identitas-saja |

Yang **tidak** disentuh: `Ed25519KeyStore`, `TerminalKeyInstaller`/`KeyManagerGateway`/`EdcSdkTerminalKeyInstaller`, `Ed25519RequestSigner`/`SigningInterceptor`, `Evidence`/`ProvisioningJournal`, `Kcv`, `BcProvider`, `RequestHeadersInterceptor`, scanner (`EdcSdkScanner`/`CameraQrScanner`/`QrScanSource`), `GateFragment`/`ScanQrFragment`/`ProcessingFragment` (kecuali `ResultFragment`).
