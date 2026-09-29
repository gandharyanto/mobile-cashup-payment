# QRIS Dynamic — `:qris-core` + `:feature-qris` — Design Spec

**Tanggal:** 2026-09-29
**Status:** desain disetujui user (sesi 2026-09-29), menunggu review spec tertulis
**Cakupan:** alur pembayaran **QRIS Dynamic** end-to-end: tombol QRIS di layar nominal → generate QR ber-amount → tampil di layar → tunggu konfirmasi (MQTT push + polling `inquiries`) → hasil dibayar / kedaluwarsa / gagal. **Tidak** termasuk: QRIS Static, cetak struk, cancel/refund, TLS/login MQTT (lihat §10).

Kontrak backend: `qris-mobile-api.md` (dokumen tim backend, 2026-09-28; mtime 15:26 WIB), dirujuk di bawah sebagai "dokumen API". Dokumen desain induk: `docs/EDC_PAYMENT_APP_DESIGN.md` §3 (rencana modul `qris-core`), J5 (Standalone Sale QRIS Dynamic).

---

## 1. Latar belakang

Saat ini tombol QRIS di `CardPaymentFragment.kt:74` (`binding.payQris`) hanya memanggil `showUnavailable()` → Toast "Fitur belum tersedia" (`R.string.card_payment_not_available`). Tidak ada modul, layar, DTO, atau pemanggilan API QRIS di repo. Yang sudah siap dipakai:

- `:notification-core` — `PaymentStatusWaiter` (race MQTT push vs polling), `MqttNotificationTransport` (Paho, `tcp://host:port`, topic `cashup/staging/v1/devices/<deviceId>`), config `AppConfig.mqttHost/mqttPort`. Belum dipakai siapa pun (belum ada wiring di `AppContainer`).
- `:signing-core` + `:common-core` — `SigningInterceptor`, `RequestHeadersInterceptor`, `safeEnvelopeCall`, `ApiResult`. Pola pemakaiannya ada di `SaleRepository.create(...)` (`:cdcp-core`).

## 2. Modul & dependency graph

Dua modul baru, mengikuti pasangan `:cdcp-core` / `:feature-card-payment`:

- **`:qris-core`** — Android library (sama seperti `:cdcp-core`; Retrofit + OkHttp). Domain + HTTP + tracker status. Tanpa UI.
- **`:feature-qris`** — Android library dengan UI (Fragment, ViewModel, layout). Bergantung ke `:qris-core`.

`gradle/module-boundaries.gradle.kts` — perubahan `allowedProjectDeps`:

```kotlin
":qris-core" to setOf(":common-core", ":signing-core", ":notification-core"),
":feature-qris" to setOf(":common-core", ":qris-core"),

":app" to setOf(
    ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
    ":feature-card-payment", ":notification-core",
    ":qris-core", ":feature-qris",                 // tambah
),
```

`:app` dijadwalkan bergantung ke `:notification-core` di spec 2026-09-28 §2, tetapi `app/build.gradle.kts` saat ini belum memuatnya (hanya `provisioning-core`, `cdcp-core`, `device-sdk-edcsdk`, `feature-card-payment`, `device-sdk-factory`); plan implementasi menambahkannya bersama modul baru. `:feature-card-payment` **tidak** bergantung ke `:feature-qris` (lihat §7, navigasi lewat deep-link).

`:qris-core` adalah library Android, jadi batasan subset API 23 tetap berlaku (tanpa `java.time`, `java.util.Base64`, dsb.). Konsekuensi desain: **tidak ada parsing timestamp ISO-8601** di modul ini (lihat §5, countdown).

## 3. `:qris-core` — kontrak

Paket `com.cashup.qris`.

### 3.1 API (internal, Retrofit)

```kotlin
internal interface QrisApi {
    @GET("v1/qris/pay-apps")
    suspend fun payApps(): Response<ApiEnvelope<List<PayAppDto>>>

    @POST("v1/qris/qr-codes")
    suspend fun generate(@Header("Idempotency-Key") key: String,
                         @Body body: GenerateQrRequest): Response<ApiEnvelope<QrCodeDto>>

    @POST("v1/qris/inquiries")
    suspend fun inquiry(@Header("Idempotency-Key") key: String,
                        @Body body: InquiryRequest): Response<ApiEnvelope<InquiryDto>>
}
```

Client dibangun persis seperti `SaleRepository.create`: `RequestHeadersInterceptor` **lebih dulu**, lalu `SigningInterceptor(signer)` (urutan mengikat — lihat KDoc `RequestHeadersInterceptor`); logging body hanya di debug dengan `redactHeader("X-Signature")`. Header per dokumen API: `X-Device-Id`, `X-Timestamp`, `X-Nonce`, `X-Signature` (semua sudah dihasilkan interceptor yang ada), `Idempotency-Key` (POST saja, di atas).

Kecocokan skema signing dengan dokumen API sudah dicek terhadap kode:

| dokumen API | `Ed25519RequestSigner` / `SigningInterceptor` |
|---|---|
| canonical: `METHOD\npath\ndeviceId\ntimestamp\nnonce\nsha256hex(body)` | identik (`canonicalize`) |
| path **tanpa query** (`static-qr-codes?…` — di luar scope, tapi aturannya sama) | `request.url.encodedPath` |
| GET meng-hash string kosong | body `null` → `ByteArray(0)` |
| `X-Signature` Base64URL tanpa padding | `encodeSignature` |
| `X-Timestamp` ISO-8601, ±5 menit | `isoTimestamp()` → `yyyy-MM-dd'T'HH:mm:ss+07:00` (detik, tanpa milidetik; contoh dokumen API pakai UTC+milidetik — keduanya ISO-8601 dengan offset, dan jalur yang sama sudah dipakai `/v1/cdcp/sales`; diverifikasi ulang di smoke test, lihat §9) |

### 3.2 Model domain (publik)

```kotlin
data class PayApp(
    val qrisHostId: Int, val hostName: String,
    val payAppName: String?, val payAppLogoRef: String?,
    val qrSupport: QrSupport,            // DYNAMIC | STATIC | BOTH ; nilai tak dikenal -> STATIC (tidak ditawarkan)
    val qrExpirySeconds: Int,
) { val supportsDynamic: Boolean }

data class GeneratedQr(
    val transactionId: String, val invoiceNumber: String?, val baseAmount: BigDecimal,
    val qrisHostId: Int, val payAppName: String?, val merchantName: String?,
    val qrContent: String, val merchantTrxId: String?,
)

enum class QrisStatus { INITIATED, AUTHORIZED, SETTLED, EXPIRED, FAILED, UNKNOWN }
// isPaid = AUTHORIZED | SETTLED ; isFinal = bukan INITIATED dan bukan UNKNOWN

data class QrisPaymentResult(
    val transactionId: String, val status: QrisStatus, val amount: BigDecimal,
    val invoiceNumber: String?, val rrn: String?, val approvalCode: String?,
    val issuerName: String?, val customerName: String?, val customerPan: String?,
    val paidAtRaw: String?,               // string mentah dari server, TIDAK di-parse (API 23)
    val pushDetail: QrisPushDetail?,      // mid/tid/nmid/acquirer/... kalau konfirmasi datang lewat push
)
```

`supportsCancel`/`supportsRefund` **tidak** dimodelkan: dokumen API menyatakan keduanya belum tersedia (`false`), dan cancel/refund di luar scope.

Status yang tidak dikenal → `UNKNOWN`, diperlakukan sebagai "masih menunggu" (tidak final) — aman terhadap penambahan status di backend. Keputusan cabang selalu pada `error.code`, bukan `message` (dokumen API §Responses; sudah ditegakkan `safeEnvelopeCall`).

### 3.3 `QrisRepository`

```kotlin
class QrisRepository private constructor(...) {
    suspend fun payApps(): ApiResult<List<PayApp>>
    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>
    suspend fun inquiry(transactionId: String): ApiResult<QrisPaymentResult>

    companion object { fun create(baseUrl: String, signer: DeviceSigner, debugLogging: Boolean = false): QrisRepository }
}
```

- `generate`: `attemptId` dipakai sebagai `Idempotency-Key` **dan** dasar `merchantTrxId` (`"APP-$attemptId"`), sehingga retry setelah gagal jaringan memakai kunci + body yang sama (mirror `SaleRepository.pending`: request dibekukan per `attemptId` sampai sukses). `posReference` tidak dikirim (YAGNI). `baseAmount` dikirim sebagai angka JSON (dokumen API: number ≥ 0.01).
- `inquiry`: **`Idempotency-Key` baru di setiap pemanggilan** (dokumen API: kunci yang sama memutar ulang jawaban pertama — akan membekukan status di `INITIATED` selamanya). Ini ditegakkan test (§9).
- Semua lewat `safeEnvelopeCall`; kesalahan dikembalikan sebagai `ApiResult.Failure(ApiError(code, message, httpStatus))`.

### 3.4 `QrisPaymentTracker`

Membungkus `PaymentStatusWaiter` (spec 2026-09-28 §3) dan `QrisRepository.inquiry`:

```kotlin
class QrisPaymentTracker(
    private val repository: QrisRepository,
    private val waiter: PaymentStatusWaiter,
    private val transport: PaymentNotificationTransport,   // hanya utk isConnected
    private val json: (String) -> ParsedPush?,             // parser envelope, default Gson; injeksi utk test
) {
    /** Suspend sampai hasil final atau kedaluwarsa. Tidak pernah melempar selain CancellationException. */
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult
}
```

Perilaku (semua sesuai dokumen API §3 & §5):

1. `waiter.await` dengan `T = TrackerSignal` (sealed: `Push(detail)` | `Polled(result)`):
   - `matches`: parse `RawNotification.payload`; terima hanya bila `v` dikenal (=1), `type == "qris.payment_paid"`, `module == "qris"`, dan `data.transactionId == qr.transactionId`; `id` yang sudah pernah diproses dibuang (QoS 1 bisa ganda). Payload rusak / tipe tak dikenal / transaksi lain → `null` (skip, bukan exception — spec 2026-09-28 §6). `data.detail` opsional; kunci `detail` yang hilang tidak boleh menggagalkan parsing. `data.reference` **tidak** dipakai untuk mencocokkan.
   - `statusCheck`: `repository.inquiry(...)`; mengembalikan `Polled(result)` **hanya bila `status.isFinal`**, selain itu `null` (lanjut polling). Kegagalan (`ApiResult.Failure`) → `null` (diabaikan, coba lagi) — exception juga sudah ditelan `PaymentStatusWaiter`.
   - `timeoutMs = 5_000`; `pollIntervalMs = 10_000` bila `transport.isConnected`, `3_000` bila tidak (dokumen API: polling lambat saat push hidup, 3–5 dtk saat tidak).
2. Hasil `Push` → **satu kali** `inquiry` untuk konfirmasi (push = petunjuk cepat, `inquiries` = sumber kebenaran). Kalau konfirmasi final → kembalikan hasilnya dengan `pushDetail` ditempelkan. Kalau belum final atau gagal → lanjut polling langsung (interval 3 dtk) sampai final/kedaluwarsa.
3. Batas waktu: seluruh `track` dibungkus `withTimeoutOrNull(expirySeconds * 1000L + 5_000L)`. Saat batas terlampaui: **satu inquiry terakhir**; bila final → kembalikan; bila tidak → `QrisStatus.EXPIRED` (lokal). `expirySeconds` diambil dari `PayApp.qrExpirySeconds` (§5).
4. `transport.disconnect()` tidak pernah dipanggil (lifecycle milik `:app`, sama seperti `PaymentStatusWaiter`).

## 4. `QrisDependencies` (kontrak ke UI)

Mengikuti pola `CardPaymentDependencies` / `CardPaymentDependenciesOwner`:

```kotlin
interface QrisDependencies {
    fun isTransactionAllowed(): Boolean = true          // re-read di batas transaksi, sama seperti card
    suspend fun ensureNotificationsConnected()          // idempotent; kegagalan ditelan (polling jadi jalur utama)
    suspend fun payApps(): ApiResult<List<PayApp>>
    suspend fun generate(qrisHostId: Int, amount: BigDecimal, attemptId: String): ApiResult<GeneratedQr>
    suspend fun track(qr: GeneratedQr, expirySeconds: Int): QrisPaymentResult
}
interface QrisDependenciesOwner { val qrisDependencies: QrisDependencies }
```

`AppContainer` mengimplementasikannya (kini sudah `CardPaymentDependencies`); `CashupApp` juga mengimplementasikan `QrisDependenciesOwner`. `:feature-qris` tidak melihat `QrisRepository`/tracker langsung — hanya `QrisDependencies` + model domain publik `:qris-core`.

## 5. `:feature-qris` — UI

Satu Fragment `QrisFragment` + `QrisViewModel`.

**Argumen** (lewat deep-link, §7): `amount: Long` (rupiah utuh, dari kalkulator). Diubah ke `BigDecimal` untuk `baseAmount`.

**State machine** (`QrisUiState`):

```
LOADING_APPS → (SELECT_APP jika >1 pay-app DYNAMIC/BOTH) → GENERATING → SHOWING_QR → PAID | EXPIRED | FAILED
                                                                          ^ FAILED bisa retry → GENERATING (attemptId baru)
```

- `LOADING_APPS`: `ensureNotificationsConnected()` (paralel, non-blocking) + `payApps()`; filter `supportsDynamic`. Kosong → `FAILED("QRIS tidak tersedia untuk merchant ini")`. Tepat satu → langsung `GENERATING`. Lebih dari satu → `SELECT_APP` (dialog daftar, pola sama dengan `SELECT_READER` di `CardPaymentFragment`).
- `GENERATING`: `generate(...)` dengan `attemptId` baru per aksi pengguna (UUID); `retry` dari `FAILED` memakai `attemptId` **yang sama** hanya bila kegagalannya `NETWORK` (request bisa saja sudah sampai server), selain itu `attemptId` baru.
- `SHOWING_QR`: render `qrContent` → bitmap, nominal, nama pay-app / merchant, **countdown lokal** dari `qrExpirySeconds` (dimulai saat QR diterima; tidak parse `expiresAt`/`qrGeneratedAt` karena API 23 tanpa `java.time` dan supaya kebal terhadap selisih jam device–server). Jalankan `track(...)` di coroutine terpisah; tombol Batal/Back membatalkan job tracking (QR tidak di-cancel di backend — belum ada API-nya; QR tetap valid sampai kedaluwarsa, dicatat di §10).
- `PAID`: tampilkan amount aktual, nama pay-app/issuer bila ada, `rrn`/`approvalCode` bila ada (baris `null` disembunyikan — dokumen API: "hide that line, don't show null"), tombol Selesai.
- `EXPIRED` / `FAILED`: pesan + tombol "Buat QR baru" / "Kembali".
- Tombol cancel/refund **tidak ada** (belum tersedia di backend).

**Render QR:** `com.google.zxing:core:3.5.3` (versi yang sama dengan `:app`) — `QRCodeWriter().encode(qrContent, BarcodeFormat.QR_CODE, size, size)` → `Bitmap` di `Dispatchers.Default`, bukan di main thread. ZXing ditambahkan sebagai `implementation` `:feature-qris`.

**Pemetaan `error.code` → pesan** (Bahasa Indonesia, di `strings.xml`; kode lain → pesan generik + kode di baris kecil untuk teknisi):

| kode | pesan pengguna |
|---|---|
| `NETWORK` | Tidak ada koneksi. Coba lagi. |
| `AMOUNT_OUT_OF_RANGE` | Nominal di luar batas QRIS merchant. |
| `DEVICE_NOT_ACTIVE`, `MERCHANT_SUSPENDED`, `MERCHANT_CHANNEL_INACTIVE` | Terminal/merchant tidak dapat memakai QRIS. |
| `QRIS_HOST_NOT_ACTIVE`, `QRIS_ACCOUNT_NOT_FOUND`, `QRIS_HOST_AMBIGUOUS` | Penyedia QRIS tidak valid. Pilih ulang. |
| `ACQUIRER_REJECTED` | Ditolak oleh penyedia QRIS. |
| `ACQUIRER_UNAVAILABLE` | Penyedia QRIS sedang gangguan. Coba lagi nanti. |
| `TIMESTAMP_OUT_OF_RANGE` | Jam terminal tidak sesuai. Periksa tanggal & jam. |
| `DEVICE_SIGNATURE_*`, `DEVICE_CREDENTIAL_NOT_FOUND` | Autentikasi terminal gagal. Hubungi teknisi. |
| `IDEMPOTENCY_KEY_IN_PROGRESS` | (retry otomatis sekali setelah jeda singkat) |

## 6. Wiring di `:app`

- `AppContainer`:
  - `qrisRepository by lazy { QrisRepository.create(config.baseUrl, deviceSigner, BuildConfig.DEBUG) }`.
  - `notificationTransport by lazy { MqttNotificationTransport(config.mqttHost, config.mqttPort) }`; `PaymentStatusWaiter` + `QrisPaymentTracker` di atasnya.
  - `ensureNotificationsConnected()` = `activeDeviceId()?.let { transport.connect(it) }` di `Dispatchers.IO`, tidak melempar. **Menyimpang dari spec `notification-core` §5** ("connect saat startup"): koneksi dibuat **lazily saat layar QRIS pertama dibuka**, lalu tetap hidup selama proses (idempotent, tidak pernah `disconnect()` dari alur pembayaran). Alasannya: startup app sudah berat (init key RSA/Ed25519, terminal 1 GB) dan belum ada konsumen lain untuk push; tanpa risiko kehilangan push karena subscribe terjadi sebelum QR digenerate.
  - `isTransactionAllowed()` yang sudah ada dipakai ulang untuk `QrisDependencies`.
- `CashupApp` menambah `QrisDependenciesOwner`.
- `nav_home.xml`: destinasi `nav_qris` (`QrisFragment`) dengan argumen `amount` (long) dan `<deepLink app:uri="cashup://qris?amount={amount}" />`.
- `build.gradle.kts` `:app` dan `settings.gradle.kts` untuk modul baru.

## 7. Perubahan di `:feature-card-payment`

Hanya tombol QRIS. `CardPaymentFragment.kt:74` menjadi:

```kotlin
binding.payQris.setOnClickListener {
    val amount = calculator.current().amount
    if (amount <= 0) toast(R.string.card_payment_invalid_amount)
    else findNavController().navigate(Uri.parse("cashup://qris?amount=$amount"))
}
```

Deep-link URI dipakai supaya `:feature-card-payment` tidak bergantung ke `:feature-qris` atau id navigasi milik `:app` (batas modul §2). `otherPayment` tetap `showUnavailable()`. Dependensi `androidx.navigation:navigation-fragment-ktx` ditambahkan ke `:feature-card-payment` bila belum ada (dicek di plan). `card_payment_not_available` tetap dipakai `otherPayment`.

## 8. Error handling & edge case

- **Push ganda** (QoS 1): dedupe by `id` di tracker (§3.4).
- **Push untuk transaksi lain / QR lama**: dibuang (`transactionId` tidak cocok); tidak menghentikan penantian.
- **Push datang, inquiry belum final** (race server): lanjut polling — jangan tampilkan "dibayar" hanya dari push.
- **Jaringan putus saat menunggu**: polling gagal ditelan dan diulang; MQTT `automaticReconnect` Paho memulihkan push. QR tetap tampil.
- **Broker tidak terjangkau**: `ensureNotificationsConnected()` gagal diam-diam; tracker memakai interval polling 3 dtk (`isConnected == false`).
- **Device dinonaktifkan mid-transaksi** (`isTransactionAllowed()` false): dicek di awal `GENERATING`, sama seperti alur kartu.
- **Layar diputar / proses dimatikan sistem**: state hidup di `ViewModel` untuk rotasi; app **stateless** (desain induk §2), sehingga jika proses mati, QR yang sudah dibayar dipulihkan oleh *Reconciliation-on-launch* (open item backend §9 desain induk) — di luar scope.
- **Tidak ada `Thread.sleep`/waktu nyata di test**: semua waktu lewat `kotlinx-coroutines-test` (`runTest`, virtual time).

## 9. Testing

`:qris-core` (JUnit + MockWebServer + coroutines-test, seperti `SaleRepositoryTest`):
- `pay-apps`, `qr-codes`, `inquiries`: request body/path/method benar; header `X-Device-Id/Timestamp/Nonce/Signature`, `Idempotency-Key` (POST saja) ada; canonical string yang ditandatangani sesuai §3.1.
- `inquiry` memakai `Idempotency-Key` **berbeda** di dua panggilan berurutan; `generate` dengan `attemptId` sama memakai kunci + body sama.
- Pemetaan respons: setiap status (`INITIATED/AUTHORIZED/SETTLED/EXPIRED/FAILED`, plus string asing → `UNKNOWN`); `error.code` di 4xx/5xx diteruskan (`AMOUNT_OUT_OF_RANGE`, `ACQUIRER_UNAVAILABLE`, dst.); `payAppName`/`payAppLogoRef` `null` tidak menjatuhkan parsing.
- `QrisPaymentTracker` dengan fake transport (`MutableSharedFlow`) dan fake repository:
  - push cocok < 5 dtk → tepat 1 inquiry konfirmasi → hasil dengan `pushDetail`;
  - push cocok tapi inquiry `INITIATED` → lanjut polling sampai final;
  - push untuk `transactionId` lain / `type` asing / `v` asing / payload rusak → diabaikan;
  - push dengan `id` sama dua kali → diproses sekali;
  - push tanpa kunci `detail` → tetap berhasil;
  - tanpa push: polling mulai di detik ke-5, interval 10 dtk saat `isConnected`, 3 dtk saat tidak;
  - melewati batas kedaluwarsa → satu inquiry terakhir → `EXPIRED` bila belum final; `PAID` bila inquiry terakhir ternyata final;
  - `disconnect()` tidak pernah dipanggil.

`:feature-qris`: test `QrisViewModel` (fake `QrisDependencies`): tepat-satu-pay-app langsung generate; banyak → `SELECT_APP`; kosong → `FAILED`; sukses → `PAID`; error code → pesan; retry `NETWORK` memakai `attemptId` sama, error lain memakai baru; cancel membatalkan tracking.

Smoke test manual terhadap dev server (dicatat hasilnya di `docs/session_log.md`, sama seperti fase sebelumnya): generate QR sungguhan → bayar lewat simulator → push tiba (latency di port **1883** dicatat — belum pernah diukur) → inquiry `AUTHORIZED`; verifikasi format `X-Timestamp` diterima; verifikasi `baseUrl` (lihat §10).

`./gradlew checkModuleBoundaries` dan `./gradlew test` harus hijau.

## 10. Di luar scope & pertanyaan terbuka

Di luar scope (sengaja):
- **QRIS Static** — tanpa transaksi di sisi app, tanpa push (dokumen API "Not covered"), jadi tidak ada cara mengonfirmasi pembayaran; butuh desain terpisah (mis. polling riwayat merchant-scoped — desain induk §9 item 5).
- **Cetak struk** — `QrisPaymentResult.pushDetail` (mid/tid/nmid/acquirer/rrn) sudah disimpan untuk itu.
- **Cancel / refund** — dokumen API: belum tersedia. Konsekuensi: menekan Batal hanya berhenti menunggu di sisi app; QR di backend tetap valid sampai kedaluwarsa, sehingga pelanggan masih bisa membayarnya setelah kasir membatalkan. Risiko ini diterima dan hanya didokumentasikan di sini sampai API cancel tersedia; UI tidak menampilkan peringatan khusus.
- **TLS 8883, login MQTT berbasis Ed25519, topic produksi** — tetap seperti spec 2026-09-28 §8. Topic dasar `cashup/staging/v1` sudah hardcoded di `MqttNotificationTransport`; dokumen API menyebut produksi memakai base topic lain → **harus dijadikan config sebelum rilis produksi** (bukan bagian pekerjaan ini).
- **Reconciliation-on-launch** — open item backend.

Pertanyaan terbuka (tidak memblokir implementasi, diverifikasi saat smoke test):
1. **`baseUrl`**: `AppConfig.baseUrl` = `http://100.114.27.64:8080`, sedangkan env Postman QRIS berisi `http://localhost:8093`. Asumsi spec ini: endpoint `/v1/qris/*` di-serve di host yang sama dengan `/v1/cdcp/*` (Front-facing API, "Avatar Core"). Bila ternyata beda, tambah `AppConfig.qrisBaseUrl` — perubahan kecil, terisolasi di `AppContainer`.
2. **Presisi `X-Timestamp`** (detik + offset `+07:00` vs milidetik UTC di contoh dokumen API) — asumsi diterima karena jalur CDCP yang sama.
3. **Push vs port 1883** — latency/reliability belum pernah diukur di plain MQTT.
