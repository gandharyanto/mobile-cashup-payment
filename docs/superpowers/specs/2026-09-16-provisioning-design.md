# Provisioning — Design Spec

**Tanggal:** 2026-09-16
**Status:** disetujui, siap dijadikan implementation plan
**Cakupan:** J1 Provisioning (setup pertama kali) saja. J2 Re-Provisioning dan J3 Deactivation **di luar scope** — lihat §10.

Dokumen ini mengubah beberapa keputusan di `docs/EDC_PAYMENT_APP_DESIGN.md`. Daftar perubahannya ada di §9; spec induk itu harus diperbarui sebagai bagian dari implementasi.

---

## 1. Sumber kebenaran

Tiga sumber, dengan urutan kewenangan yang eksplisit:

| Sumber | Perannya | Kewenangan |
|---|---|---|
| `D:\Downloads\Provisioning.drawio` | Sequence diagram provisioning milik tim | **Tertinggi** untuk bentuk body & urutan langkah |
| `D:\gandha_cashup\projects\edc-mobile copy` | App EDC dev yang sudah terbukti jalan lawan `corepayment` | Nama path endpoint, format header, pola kripto |
| `D:\gandha_cashup\projects\edc-sdk` | SDK vendor internal, 7 vendor | Akses hardware & injeksi key |

Kalau diagram tim dan `edc-mobile` berbeda, **diagram tim menang** untuk bentuk body. Di titik yang diagram tim diam (mis. bentuk request `activate`), bentuk `edc-mobile` dipakai dan dicatat sebagai asumsi di §8.

---

## 2. Alur yang diadaptasi

Dari `Provisioning.drawio` Page-1, lima lifeline: **App Provisioning · EDC · Backend · Payment HSM · General Purpose HSM**.

```
 1  App Provisioning   ↻ Generate QR                 → { "challengeCode": "ABCD-1234" }
 2  EDC                → QR Reading
 3  EDC                ↻ Generate RSA & EDDSA Key Pair
 4  EDC   → Backend      QR Redeem
                         { challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey }
 5  Backend            ↻ Validate QR & Deactivate Challenge-code
 6  Backend → EDC        QR Redeem Response      { orderId, activationToken }
 7  EDC   → Backend      Get DUKPT               { orderId, activationToken }
 8  Backend            ↻ Generate keyId (KSN)
 9  Backend → Payment HSM          Get IPEK
10  Payment HSM → Backend          Send new IPEK
11  Backend → GP HSM               Get ED25519 Public Key
12  GP HSM → Backend               Return ED25519 Public Key
13  Backend → EDC        Send IPEK (Wrapped with RSA)
                         { orderId, wrappedPackageKey (#4 DUKPT), appEddsaPublicKey }
14  EDC   → Backend      Send KCV
15  Backend → EDC        Respond OK / NOK
16  Backend → App Provisioning     Callback Provisioning Status
```

**Langkah 16 bukan urusan app ini.** Backend yang memberitahu App Provisioning; EDC tidak perlu mengirim apa pun ke sana.

**Tidak ada login teknisi.** Diagram tim tidak punya langkah login, dan `edc-mobile` menghapus login+OTP total pada 2026-09-14. Otorisasi sepenuhnya datang dari `challengeCode` hasil scan QR. Ini membatalkan `EDC_PAYMENT_APP_DESIGN.md` §4 J1 langkah 1–2.

---

## 3. Kontrak HTTP

Base URL dari konfigurasi. Default pengembangan: `http://100.103.104.38:8080` (Raspberry Pi di Tailnet, sama dengan `edc-mobile`). Produksi menunjuk ke Front-facing API (EDC channel), yang meneruskan ke `corepayment` di belakangnya.

Path mencerminkan `corepayment` 1:1; bentuk body mengikuti diagram tim.

### 3.1 Endpoint

```
POST v1/terminal-key-provisioning/qr-redeem                    [TANPA signature]
  →  { challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey }
  ←  { orderId, activationToken }

POST v1/terminal-key-provisioning/orders/{orderId}/package     [signed]
  →  { orderId, activationToken }
  ←  { orderId, wrappedPackageKey, appEddsaPublicKey }

POST v1/terminal-key-provisioning/orders/{orderId}/activate    [signed]
  →  { activationToken, keyCheckValues: { <purpose>: <kcv hex> } }
  ←  { status }
```

`qr-redeem` tidak ditandatangani karena backend belum mengenal public key device — kunci itu baru dikirim di request yang sama. Dua request berikutnya ditandatangani.

`orderId` muncul di path **dan** body pada `/package` — konsekuensi dari mencampur path `corepayment` dengan body diagram tim. Masuk daftar konfirmasi (§8).

### 3.2 Amplop respons

Mengikuti `corepayment` (`shared/api/ApiResponse.kt`):

```json
{ "data": { }, "error": { "code": "", "message": "", "details": { } }, "meta": { "correlationId": "" } }
```

Amplop ini harus di-parse **baik pada 2xx maupun non-2xx** — `corepayment` mengirim `error` yang berguna justru di 401/403/404/409/422/503. Retrofit tidak mem-parsing body pada respons gagal kalau tipe return-nya bukan `Response<T>`, jadi semua method `ProvisioningApi` mengembalikan `Response<ApiEnvelope<T>>`.

**Selalu branch pada `error.code`, tidak pernah pada `error.message`** — `message` berbahasa Indonesia, human-facing, boleh berubah kapan saja.

### 3.3 Header

```
X-Timestamp        ISO-8601 offset            semua request
X-Correlation-Id   uuid                       semua request
X-Device-Id        = serialNumber             request bertanda tangan
X-Nonce            uuid                       request bertanda tangan
X-Signature        Ed25519, Base64 URL-safe no-padding
```

Canonical string yang ditandatangani, dipisah `\n`:

```
METHOD
path                 (encodedPath saja, TANPA query string)
deviceId
timestamp
nonce
sha256hex(body)      string kosong → hash dari byte array kosong
```

`X-Timestamp` dibuat ulang tiap percobaan HTTP; retry boleh punya timestamp baru.

**Identitas device = `serialNumber`.** Diagram tim tidak punya field `deviceId` di response `qr-redeem`, jadi `serialNumber` yang sudah dikirim saat redeem dipakai sebagai `X-Device-Id`. Ini menyimpang dari `edc-mobile` yang memakai UUID `deviceId` terbitan backend — dicatat di §8.

---

## 4. Kriptografi & penyimpanan key

### 4.1 Tiga key, tiga tempat

| Key | Algoritma | Disimpan di | Alasan |
|---|---|---|---|
| Identitas unwrap | RSA-2048 | **AndroidKeyStore**, non-extractable, `PURPOSE_DECRYPT`, StrongBox oportunistik | Unwrap IPEK terjadi di dalam TEE; private key tidak pernah ada di RAM |
| Penandatangan request | Ed25519 | BouncyCastle + `EncryptedSharedPreferences` | Tidak ada pilihan lain — lihat §4.2 |
| DUKPT (IPEK/KSN) | TDES | **Modul vendor dulu**, vault TEE sebagai cermin | Lihat §4.3 |

Ini lebih kuat dari `edc-mobile`, yang menyimpan RSA sebagai blob PKCS8 di `EncryptedSharedPreferences`.

### 4.2 Kenapa Ed25519 tidak bisa di TEE

Diverifikasi langsung terhadap `android.jar` di mesin pengembangan (`D:\Android\Sdk\platforms\`), bukan dari dokumentasi:

```
API 33, 36, 37:  KEY_ALGORITHM_3DES = "DESede"   ADA
API 33, 36, 37:  KEY_ALGORITHM_ED25519           TIDAK ADA
API 37 menambah ML-DSA, tetap tanpa Ed25519.
```

`edc-mobile` menemukan hal yang sama dan mengambil kesimpulan yang sama. Konsekuensi yang harus disadari: **signing key ini bukan hardware-backed.** Levelnya sama dengan `EncryptedSharedPreferences` biasa — dilindungi master key Keystore, tapi private key-nya sendiri melewati RAM tiap kali menandatangani.

Alternatifnya adalah pindah ke ECDSA P-256 yang bisa non-extractable di Keystore, tapi diagram tim sudah menetapkan EdDSA dan backend mengambil Ed25519 public key dari General Purpose HSM. Jadi ini **keputusan yang sudah dikunci di sisi backend**, bukan yang bisa kita ubah sendiri.

### 4.3 IPEK: modul vendor dulu, baru vault TEE

`edc-sdk` sudah menyelesaikan masalah ini dan pola itu diadopsi apa adanya.

`core/emv/BaseSystemKey.kt` mendefinisikan kontraknya, diimplementasikan untuk **pax, sunmi, centerm, nexgo, topwize, szanfu, ingenico**:

```kotlin
interface BaseSystemKey {
    fun writeKeK(kek: ByteArray): Boolean
    fun writePIK(pik: ByteArray): Boolean
    fun writeIPEK(ipek: ByteArray, ksn: ByteArray): Boolean
    fun incrementKSN(): ByteArray
    fun cryptByDukptDataKey(data: ByteArray): Map<Int, ByteArray>
}
```

Sifat yang bikin ini bernilai: **IPEK masuk, tidak pernah keluar.** Enkripsi terjadi di dalam modul lewat `cryptByDukptDataKey`; app tidak pernah memegang transaction key.

`core/KeyManager.kt` mengurutkan keduanya:

```kotlin
override fun writeIPEK(ipek: ByteArray, ksn: ByteArray): Boolean {
    val devOk = DeviceManager.systemKey?.writeIPEK(ipek, ksn) == true
    if (devOk) DuktpVaultCompat.storeIpekForSet(appCtx, keyIndex, ipek, ksn, overwrite = true)
    return devOk
}
```

Vault TEE (`DuktpVaultCompat` — AndroidKeyStore AES-256-GCM, StrongBox oportunistik) **hanya ditulis kalau modul vendor konfirmasi sukses.** Urutan ini bukan gaya; komentar di atas fungsi itu mencatat kasus produksi nyata: cache lokal pernah ditulis lebih dulu tanpa syarat, lalu timeout Bluetooth ke reader ANFU membuat cache lokal dan key asli di reader diam-diam berbeda — PIN block jadi sampah tanpa exception apa pun, baru ketahuan saat bank menolak dengan kode 81.

**Di device tanpa modul vendor**, vault TEE jadi satu-satunya penyimpanan dan DUKPT berjalan di software. Ini degradasi nyata: transaction key melewati RAM, dan ini bukan jalur PCI-PTS. App harus **mencatat kondisi ini secara eksplisit**, bukan mendiamkannya.

### 4.4 Jendela IPEK di RAM

Antara unwrap (§2 langkah 13) dan injeksi (§2 langkah 14) IPEK plaintext berada di memori app — tidak terhindarkan, karena KCV harus dihitung dari IPEK plaintext dan itu satu-satunya jendelanya. `edc-mobile` punya keterbatasan yang sama.

Mitigasi: array key di-`fill(0)` segera setelah dipakai, tidak pernah disalin ke `String`, tidak pernah masuk log.

Beberapa SDK vendor (mis. PAX `writeKey` dengan key terenkripsi di bawah TMK) memungkinkan injeksi key terbungkus tanpa plaintext pernah muncul di RAM. Itu perbaikan nyata tapi **di luar scope plan ini** — dicatat supaya tidak hilang.

---

## 5. Arsitektur module

### 5.1 Ketergantungan ke `edc-sdk`

`mobile-cashup-payment` memakai `edc-sdk` **lewat AAR, bukan source**.

AAR yang dipakai (`core-release_1.0.63.aar`, `logger-release_1.0.2.aar`, dan enam AAR vendor 1.0.63) disalin ke `aarlib/` di root repo, git-ignored, diresolusi lewat repository Gradle `flatDir`. Sumbernya: `D:\gandha_cashup\projects\edc-tms-agent\aarlib\`.

Sudah diverifikasi bahwa `core-release_1.0.63.aar` memuat `SDKManager`, `DeviceManager`, `KeyManager`, `DuktpVaultCompat`, `BaseSystemKey`, dan `BaseDeviceHelper` — jadi tidak ada binder vendor yang perlu ditulis sendiri.

**Kenapa AAR, bukan composite build:** `edc-sdk/settings.gradle` meng-`include` module yang direktorinya tidak ada di checkout saat ini (`prepaid-lib`, `cdcp-core`, `prepaid-core`, `newland`, `emvlib`), sehingga build dari source akan gagal apa adanya.

Kompatibilitas: `edc-sdk` memakai AGP 8.4.0, Kotlin 1.9.24, Gradle 8.6, `minSdk` 21, `targetSdk` 33. Repo ini memakai Kotlin 2.0.21 — aman, Kotlin 2.0 dapat mengonsumsi artifact hasil kompilasi 1.9.

### 5.2 Yang dibatalkan

`docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md` (9 task, 67 step, belum dijalankan sama sekali) **dibatalkan**. Plan itu hendak membangun ulang enam adapter vendor dari AAR mentah, dan hanya sampai `connect()` + `serialNumber()`. `edc-sdk` sudah menyediakan itu plus injeksi key, printer, card reader, dan EMV untuk tujuh vendor.

`DeviceSdkRegistry` di `device-sdk-api` **dihapus** — pencocokan prefix `Build.MODEL` menjadi mubazir karena `SDKManager.autoDetectDevice()` sudah melakukan deteksi lewat `Build.BRAND`. Tes yang menyertainya ikut dihapus.

### 5.3 Struktur

```
mobile-cashup-payment/
├── aarlib/                          AAR edc-sdk, git-ignored, flatDir
│
├── common-core/                     ADA — Kotlin/JVM, tidak berubah
│   └── network/  ApiResult · ApiError · safeApiCall · RetrofitFactory
│
├── device-sdk-api/                  ADA — Kotlin/JVM, kontrak murni
│   ├── CardReader · Printer · Scanner · Capability      tetap
│   ├── SerialNumberProvider                             baru
│   ├── TerminalKeyInstaller · TerminalKeyMaterial       baru
│   ├── Backing                                          baru
│   └── DeviceSdkRegistry                                DIHAPUS
│
├── device-sdk-edcsdk/               BARU — android.library
│   └── satu-satunya module yang mengimpor com.lib.core.*
│
├── signing-core/                    DITULIS ULANG — Kotlin/JVM
│   └── Ed25519RequestSigner · SigningInterceptor · SigningKeyProvider
│
├── provisioning-core/               BARU — android.library, TANPA UI
│   ├── data/
│   │   ├── remote/   ProvisioningApi · dto/
│   │   ├── local/    ProvisioningStateStore
│   │   └── ProvisioningRepository            → ApiResult<T>
│   ├── crypto/
│   │   ├── RsaKeyStore · Ed25519KeyStore · PackageUnwrapper · Kcv
│   └── domain/
│       ├── ProvisionDeviceUseCase            langkah 1–10, atomic
│       └── model/  ProvisioningResult · ProvisioningStep
│
└── app/                             BARU — android.application
    ├── di/           AppContainer             manual DI
    └── ui/
        ├── navigation/   Navigation Component + ViewBinding
        └── provisioning/
            ├── ProvisioningViewModel          StateFlow<ProvisioningUiState>
            └── Gate · ScanQr · Processing · Result (Fragment + layout)
```

### 5.4 Kontrak antar-module baru

Semuanya milik `device-sdk-api`, termasuk tipe datanya — `provisioning-core` bergantung ke `device-sdk-api`, tidak pernah sebaliknya.

```kotlin
// device-sdk-api
interface SerialNumberProvider {
    suspend fun serialNumber(): String?          // null kalau device tidak dikenali
}

/** Satu key DUKPT hasil unwrap, siap diinjeksi. Byte mentah, bukan String. */
class TerminalKeyMaterial(val purpose: String, val ipek: ByteArray, val ksn: ByteArray)

enum class Backing { VENDOR_SECURE_MODULE, TEE_VAULT_ONLY }

interface TerminalKeyInstaller {
    /** true hanya kalau modul vendor mengonfirmasi key benar-benar diterima. */
    suspend fun install(materials: List<TerminalKeyMaterial>): Boolean
    suspend fun wipe()
    val backing: Backing
}
```

`provisioning-core` hanya mengenal interface ini. `device-sdk-edcsdk` yang menerjemahkannya ke `SDKManager` dan `KeyManager`.

`TerminalKeyMaterial` memegang `ByteArray`, bukan `String` — supaya key material bisa di-`fill(0)` setelah dipakai (§4.4). Karena itu ia bukan `data class`: `toString()` bawaan `data class` akan membocorkan isi key ke log.

---

## 6. MVVM

Aliran satu arah: `View → event → ViewModel → UseCase → Repository → Retrofit`, balik sebagai `ApiResult` → `ProvisioningUiState` → `StateFlow` → render.

| Lapis | Tahu | Tidak tahu |
|---|---|---|
| View (Fragment) | `ProvisioningUiState`, callback event | Retrofit, kripto, SDK vendor |
| ViewModel | use case, `StateFlow`, `viewModelScope` | HTTP, Keystore, `com.lib.core.*` |
| UseCase | repository + interface device | Retrofit, Android UI |
| Repository | Retrofit, DTO, `safeApiCall` | ViewModel, UI, orkestrasi |
| `device-sdk-edcsdk` | `com.lib.core.*` | HTTP, UI, alur provisioning |

DTO tidak pernah lolos ke ViewModel; model domain yang naik.

`ProvisioningUiState` adalah **sealed interface**, bukan data class dengan `isLoading`/`error`/`data` nullable sekaligus — supaya kombinasi mustahil tidak bisa dibentuk:

```kotlin
sealed interface ProvisioningUiState {
    data object Idle : ProvisioningUiState
    data object Scanning : ProvisioningUiState
    data class Processing(val step: ProvisioningStep) : ProvisioningUiState
    data class Success(val serialNumber: String, val orderId: String, val backing: Backing) : ProvisioningUiState
    data class Failure(val code: String, val message: String, val retryable: Boolean) : ProvisioningUiState
}
```

**Keputusan teknologi:**

- **XML Views + ViewBinding**, bukan Compose. Target hardware adalah EDC dengan RAM 1 GB dan SoC kelas Cortex-A53, banyak yang jalan Android 7–11. Compose memuat ribuan kelas sebelum frame pertama dan penawarnya (Baseline Profile) baru berlaku penuh di API 28+. UI di sini empat layar yang nyaris statis, jadi keunggulan Compose hampir tidak terpakai. UI dari SDK vendor (PIN pad, prompt kartu) juga berbasis View. Catatan: `edc-mobile` memakai Compose, tapi `minSdk`-nya 33 — app itu memang tidak dirancang untuk terminal ini.
- **DI manual lewat `AppContainer`**, bukan Hilt — menghindari kapt/ksp dan overhead startup demi keuntungan yang tidak terasa di app sekecil ini. Sama dengan `edc-mobile`.
- **Gson dipertahankan**, tidak pindah ke kotlinx.serialization meski `edc-mobile` memakainya — `RetrofitFactory` yang sudah jadi memakai Gson.
- **`minSdk` 23** (`EncryptedSharedPreferences` dan Keystore mensyaratkannya), `targetSdk` 33, StrongBox oportunistik di API 28+.

---

## 7. Alur, error, dan testing

### 7.1 `ProvisionDeviceUseCase`

```
 1. serialNumber    ← SerialNumberProvider
 2. RSA keypair     ← AndroidKeyStore (non-extractable, PURPOSE_DECRYPT)
    Ed25519 keypair ← BouncyCastle → EncryptedSharedPreferences
 3. challengeCode   ← hasil scan QR
 4. qr-redeem       → orderId, activationToken
 5. package         → wrappedPackageKey
 6. unwrap          ← RSA-OAEP di dalam TEE → material DUKPT { ipek, ksn }
 7. hitung KCV per purpose
 8. install         → TerminalKeyInstaller (vendor dulu, vault TEE menyusul)
 9. activate        → kirim keyCheckValues
10. tandai provisioned
```

**Atomic** (`EDC_PAYMENT_APP_DESIGN.md` §4 J1 langkah 9): gagal di langkah mana pun → semua key dihapus (`TerminalKeyInstaller.wipe()`, hapus Ed25519, hapus state), device tetap berstatus belum terprovisioning. Tidak ada state "setengah jalan".

Langkah 6–8 berjalan di background dispatcher; UI thread hanya menerima pembaruan state (spec §11).

### 7.2 Purpose DUKPT

Diagram tim menulis "#4 DUKPT" tanpa menamainya. `materials` karena itu diperlakukan **data-driven**: purpose apa pun yang datang di paket dipasang dan KCV-nya dilaporkan. Tidak ada daftar purpose yang di-hardcode. Ini membuat jumlah dan nama purpose bukan penghalang implementasi, tapi tetap harus dikonfirmasi (§8).

### 7.3 Error handling

`safeApiCall` yang sudah ada memetakan HTTP → `ApiResult`. Amplop `{error:{code,message}}` di-unwrap untuk 2xx maupun non-2xx, dan branch **selalu pada `code`**.

Satu tabel `code → pesan operator`, mengikuti pola `ErrorHints.kt` di `edc-mobile`. Kode yang sudah dikenal dari `corepayment`:

```
PROVISIONING_TOKEN_INVALID          token/QR tidak valid, sudah dipakai, atau kedaluwarsa
TERMINAL_KEY_ALREADY_PROVISIONED    device sudah pernah diprovisioning penuh
TERMINAL_NOT_REGISTERED             device belum terdaftar di backend
TERMINAL_INACTIVE                   device tidak aktif
DEVICE_SIGNATURE_REQUIRED           signature tidak diterima backend
```

Daftar definitif untuk Front-facing API belum ada (§8). Kode yang tidak dikenal jatuh ke `error.message` apa adanya.

### 7.4 Testing

| Lapisan | Cara |
|---|---|
| `ProvisionDeviceUseCase` | MockWebServer + `TerminalKeyInstaller` palsu — happy path dan tiap jalur gagal, termasuk verifikasi rollback atomic |
| KCV | Vektor uji, port `Kcv.kt` dari `edc-mobile` |
| Canonical string + Ed25519 | Golden test, string kanonik dikunci byte-exact |
| `ProvisioningRepository` | MockWebServer — amplop sukses, amplop error di non-2xx, body bukan JSON |
| `RsaKeyStore` | Tidak bisa unit test JVM; di balik interface `RsaUnwrapper`, fake untuk test, yang nyata diverifikasi manual |
| `device-sdk-edcsdk` | **Tidak bisa ditest tanpa hardware** — gap yang dicatat eksplisit, divalidasi manual di terminal fisik |

Gap hardware ini sama sifatnya dengan yang sudah diakui plan vendor-adapters sebelumnya: binder vendor menyentuh framework Android dan binder nyata, tidak bisa diuji tanpa terminal fisik atau Robolectric yang jauh lebih berat.

---

## 8. Yang harus dikonfirmasi ke tim backend

Delapan hal. Tiga pertama bisa memblokir integrasi kalau tebakannya salah.

1. **Kapasitas RSA-2048 OAEP-SHA256 hanya 190 byte.** Empat pasang (IPEK 16B + KSN 10B) = 104 byte kalau dikirim biner padat — muat. Tapi kalau dibungkus JSON + base64 seperti lazimnya (~280 byte), **tidak muat**. `edc-mobile` memakai skema hibrida (RSA membungkus kunci AES, payload di AES-GCM) justru karena batasan ini. Diagram menulis `wrappedPackageKey` tunggal — perlu dipastikan backend mengirim apa persisnya.
2. **Nama dan jumlah purpose DUKPT.** Diagram menulis "#4 DUKPT"; `edc-mobile` memakai tiga (TRACK/AMOUNT/PIN). Implementasi data-driven jadi tidak terblokir, tapi KCV harus dilaporkan dengan nama purpose yang backend harapkan.
3. **Bentuk request `activate`.** Diagram hanya menulis "Send KCV". Spec ini memakai bentuk `edc-mobile` (`activationToken` + `keyCheckValues`), tanpa `deviceSignature` di body karena sudah ada di header. Perlu dikonfirmasi.
4. **`serialNumber` sebagai `X-Device-Id`.** Diagram tim tidak punya `deviceId` di response redeem. Perlu dipastikan backend memang mengenali device lewat serial number, bukan UUID terbitannya sendiri.
5. **Path Front-facing API.** Spec ini memakai path `corepayment` 1:1. Perlu dipastikan Front-facing API benar-benar mencerminkannya, termasuk `orderId` yang muncul di path sekaligus body pada `/package`.
6. **`GET` vs `POST` untuk `/package`.** `corepayment` memakai `GET` dengan header `X-Activation-Token`; diagram tim memakai body. Spec ini mengikuti diagram (POST + body).
7. **Format canonical string & signature.** Diagram tidak menyebutkannya sama sekali. Spec ini memakai format `corepayment`, satu-satunya yang sudah terbukti. Encoding signature Ed25519 (raw 64 byte, Base64 URL-safe no-padding) perlu dikonfirmasi.
8. **Daftar kode error provisioning** untuk Front-facing API.

---

## 9. Perubahan yang harus dibuat di `EDC_PAYMENT_APP_DESIGN.md`

| § | Sekarang berbunyi | Harus jadi |
|---|---|---|
| §2 | Auth di-bootstrap lewat login teknisi → temp JWT | Tidak ada login. Otorisasi dari `challengeCode` hasil scan QR |
| §2 | Signing key disimpan di Keystore, "TEE floor, StrongBox oportunistik" | Signing key Ed25519 **tidak** hardware-backed (§4.2). Yang di TEE: RSA unwrap + vault DUKPT |
| §3 | Sembilan module adapter vendor (`device-sdk-feitian`, `-pax`, …) | Satu module `device-sdk-edcsdk` di atas AAR `edc-sdk` |
| §4 J1 | Langkah 1–2 login teknisi, langkah 5 memanggil provisioning endpoint | Langkah 1–2 dihapus; alur jadi §2 dokumen ini |
| §9 item 1 | Signature scheme masih terbuka (HMAC? RSA? ECDSA?) | Ed25519, dikunci diagram tim. Sisa yang terbuka tinggal encoding (§8 item 7) |
| §9 item 2 | Kontrak provisioning API belum ada | Terjawab di §3 dokumen ini; sisanya jadi §8 |

`docs/diagrams/` (belum di-commit) memuat kontrak 16-endpoint yang **dikarang sebelum diagram tim ditemukan** — `POST /v1/auth/technician` dan `POST /v1/provisioning/activate` di sana tidak pernah ada. Halaman provisioning-nya harus diregenerate dari §2–§3 dokumen ini, atau dibuang.

---

## 10. Di luar scope

- **J2 Re-Provisioning** dan **J3 Deactivation** — diagram tim tidak punya endpoint untuk keduanya; menebak kontraknya sekarang hampir pasti jadi kerja yang dibuang.
- **`POST v1/cdcp/sales`** dan seluruh domain pembayaran — plan CDCP tersendiri.
- **Injeksi key terbungkus** tanpa plaintext melewati RAM (§4.4).
- **Printer, card reader, EMV** — `edc-sdk` menyediakannya, tapi tidak ada yang dipakai di alur provisioning.
- **Vendor di luar tujuh yang punya implementasi `edc-sdk`** (feitian, urovo, newland, tianyu tidak punya `SystemKey`).
