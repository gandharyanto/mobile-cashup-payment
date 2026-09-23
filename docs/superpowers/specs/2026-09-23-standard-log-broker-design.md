# Standard Log Broker (RabbitMQ `logs.persist`) — Design Spec

**Tanggal:** 2026-09-23
**Status:** disetujui, siap dijadikan implementation plan
**Cakupan:** publish log transaksi terstruktur ke broker AMQP (RabbitMQ, queue `logs.persist`) dari project `mobile-cashup-payment`. Konektivitas MQTT untuk payment-status (host lain, akan datang) **di luar scope** dokumen ini, tapi arsitekturnya sengaja disiapkan supaya modul transport bisa dipakai ulang untuk itu tanpa perubahan kontrak.

---

## 1. Latar belakang

`mobile-apps-cashlez-softpos` (project sibling, path `D:/gandha_cashup/projects/mobile-apps-cashlez-softpos`) sudah punya pipeline logging terstandarisasi yang publish payload JSON ter-HMAC-sign ke RabbitMQ exchange `logs`, routing key `logs.service`, queue `logs.persist` — dikonsumsi tim backend untuk audit trail transaksi (CDCP, QRIS, VA, BNPL, SoftPOS, dst). Referensi kode lengkap: `common-core/src/main/java/com/common/core/logger/{StandardLogPayload,StandardLogRabbitClient}.kt`, `common-core/src/main/java/com/common/core/broker/{Auth,RabbitmqClientHelper}.kt`.

`mobile-cashup-payment` (project ini) adalah rewrite baru dan **belum punya modul broker/logging apa pun** — lihat `docs/superpowers/specs/2026-09-22-vendor-sdk-ui-transaction-adoption-roadmap.md` untuk gambaran besar rewrite ini. Tujuan dokumen ini: port pipeline `logs.persist` ke project ini, dengan dua perbedaan struktural disengaja dari project asal:

1. **Modul transport terpisah dari logic domain logging** (`:broker-core` vs penambahan di `:common-core`), karena akan ada koneksi broker kedua (MQTT, host berbeda, untuk payment-status) yang harus bisa pakai transport yang sama tanpa ikut menyeret logic HMAC/payload logging.
2. **Tidak ada flow login user** di project ini (beda dari `mobile-apps-cashlez-softpos` yang fetch kredensial broker setelah login merchant) — project ini masuk lewat provisioning device. Kredensial broker karena itu **di-hardcode ter-obfuscate** di source, bukan di-fetch dari endpoint `/internal/secret-key` seperti project asal. Ini keputusan sadar dari user, lihat §7 untuk detail & trade-off-nya.

## 2. Modul & dependency graph

Dua modul baru, keduanya masuk tier "kontrak murni" (`emptySet()` di `module-boundaries.gradle.kts`, sejajar `:common-core`/`:device-sdk-api`/`:signing-core`):

- **`:secure-storage-core`** — penyimpanan terenkripsi-at-rest generik (`EncryptedSharedPreferences` + Android Keystore). Menggantikan `SecurePrefs` yang saat ini `internal` di `:provisioning-core` (duplikasi dihindari, lihat §4).
- **`:broker-core`** — transport pub/sub generik. Implementasi pertama: `AmqpBrokerClient` (RabbitMQ). Kontrak `BrokerClient` dirancang supaya implementasi MQTT bisa ditambah nanti tanpa ubah consumer.

Perubahan pada modul yang sudah ada:

- **`:common-core`** dependency bertambah ke `:broker-core` (paket `logging` baru berisi `StandardLogPayload`/`StandardLogHmacSigner`/`StandardLogSanitizer`/`StandardLogPublisher`, port dari project asal, disesuaikan §5).
- **`:provisioning-core`** dependency bertambah ke `:secure-storage-core`; `SecurePrefs` internal-nya dihapus, pakai yang shared.
- **`:app`** dependency bertambah ke `:broker-core` (inisialisasi koneksi broker saat startup, mirror pola `EnvironmentConfig.init()` project asal tapi tanpa daftar environment — cuma satu broker AMQP untuk sekarang).

`module-boundaries.gradle.kts` — perubahan konkret ke `allowedProjectDeps`:

```kotlin
":secure-storage-core" to emptySet(),
":broker-core" to emptySet(),

":common-core" to setOf(":broker-core"),                              // sebelumnya emptySet()
":provisioning-core" to setOf(":common-core", ":device-sdk-api", ":signing-core", ":secure-storage-core"), // tambah :secure-storage-core
":app" to setOf(
    ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
    ":feature-card-payment", ":broker-core",                          // tambah :broker-core
),
```

`:feature-card-payment` **tidak** bertambah dependency baru — tetap cuma lihat `:common-core` (yang sudah diizinkan), StandardLogPublisher diakses lewat sana.

Diagram dependency (panah = "boleh depend on"):

```
:secure-storage-core   :broker-core   :device-sdk-api   :signing-core
        ^                    ^
        |                    |
:provisioning-core     :common-core  (StandardLog* pipeline hidup di sini)
                              ^
                              |
                    :feature-card-payment
                              ^
                              |
                            :app  (juga langsung depend :broker-core, untuk init koneksi)
```

## 3. Kenapa crypto TIDAK digabung jadi satu module

Dipertimbangkan & ditolak: menyatukan `:secure-storage-core` dengan `:signing-core` (Ed25519 request signing), RSA/TR-34 provisioning crypto (`:provisioning-core/data/crypto/*`), dan DUKPT PIN-block crypto (`:cdcp-core/crypto/*`) jadi satu module "crypto-core".

Alasan penolakan:
- `module-boundaries.gradle.kts` sengaja menegakkan modul sempit/single-purpose — konsolidasi serupa untuk `device-sdk-*` sudah pernah dicoba tim dan dibatalkan (`docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md`).
- Domain keamanan berbeda: DUKPT/PIN-block (data kartu, PCI-scoped) vs RSA/TR-34 (terminal key injection) vs Ed25519 (request signing) vs "sembunyikan password broker" (infra secret) idealnya diisolasi satu sama lain untuk meminimalkan blast radius & audit surface per domain.
- Satu-satunya duplikasi nyata adalah pola penyimpanan (`EncryptedSharedPreferences`/`MasterKey`), yang memang infra generik, bukan pilihan algoritma kriptografi payment — itu saja yang ditarik jadi `:secure-storage-core`. Ed25519/RSA/TR-34/DUKPT tetap di modul masing-masing, tidak disentuh.

## 4. `:secure-storage-core`

Port dari `provisioning-core/src/main/kotlin/com/cashup/provisioning/data/local/SecurePrefs.kt`, dibuat publik (bukan `internal`) dan dipindah ke module baru:

```kotlin
package com.cashup.securestorage

object EncryptedPrefs {
    fun open(context: Context, fileName: String): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context, fileName, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }
}
```

Dependency: `androidx.security:security-crypto:1.1.0-alpha06` (versi yang sama persis dengan yang sudah dipakai `:provisioning-core` sekarang — jangan naikkan versi sebagai bagian dari perubahan ini, di luar scope).

`:provisioning-core` diupdate untuk memakai `EncryptedPrefs.open(...)` ini menggantikan `SecurePrefs` lokalnya; `SecurePrefs.kt` lama dihapus. Test yang sudah ada untuk jalur ini (lihat komentar `testOptions` di `provisioning-core/build.gradle.kts` soal `android.util.Base64` + Robolectric) harus tetap hijau — konvensi Robolectric yang sama berlaku untuk test baru di `:secure-storage-core`.

## 5. `:broker-core`

### 5.1 Kontrak transport

```kotlin
package com.cashup.broker

data class BrokerAuth(
    val host: String,
    val port: Int,
    val username: String? = null,
    val password: String? = null,
    val virtualHost: String = "/",
    val useTls: Boolean = false,
)

interface BrokerClient {
    val isConnected: Boolean
    suspend fun connect(): Boolean
    suspend fun publish(exchange: String, routingKey: String, payload: ByteArray): Boolean
    fun disconnect()
}
```

Nama disengaja generik (`exchange`/`routingKey`, bukan `queue`) supaya cocok untuk model pub/sub AMQP *dan* MQTT topic-based sekaligus — implementasi MQTT nanti bisa treat `exchange` sebagai no-op dan `routingKey` sebagai topic.

### 5.2 `AmqpBrokerClient`

Port dari `RabbitmqClientHelper.kt` project asal, dengan perbaikan keamanan yang sudah ada di sana dipertahankan (bukan diregresi):
- `com.rabbitmq:amqp-client` — pin ke rilis stabil 5.x terbaru saat implementasi (jangan otomatis pakai `5.9.0` project asal tanpa cek — itu sudah beberapa tahun, cek CVE terbaru saat implementation plan dibuat).
- TLS eksplisit `TLSv1.2` + `enableHostnameVerification()` (bukan `useSslProtocol()` tanpa argumen / trust-everything — itu yang sudah pernah diperbaiki di project asal sebagai temuan security, jangan diulang di sini).
- `isAutomaticRecoveryEnabled = true`, reconnect saat `IOException` di `connect()`.

### 5.3 Kredensial

`AmqpBrokerClient` menerima `BrokerAuth` dari caller (`:app` saat init) — **tidak** resolve kredensial sendiri dari storage. Resolusi kredensial (baca §7) jadi tanggung jawab `:app`, yang membaca dari secret ter-obfuscate + `:secure-storage-core`, lalu construct `BrokerAuth` dan pass ke `AmqpBrokerClient` saat `init()`.

## 6. Penambahan `:common-core` — pipeline `StandardLog`

Paket baru `com.cashup.common.logging.standard` (nama dibedakan dari `PaymentLogger` yang sudah ada di `com.cashup.common.logging` — itu logger lokal Logcat-style, tidak berhubungan, tetap dipertahankan apa adanya):

- `StandardLogPayload` — sama seperti project asal (`correlationId`, `timestamp`, `source`, `deviceId`, `merchantId`, `target`, `service`, `httpHeader`/`jsonBody` tersanitasi, `hmac`).
- `StandardLogHmacSigner` — HMAC-SHA256 identik: `sign("{correlationId}.{service}.", key=secret)`, hex-encoded. Backend sudah punya kontrak ini, **jangan diubah**.
- `StandardLogSanitizer` — redact field sensitif (daftar key sama seperti project asal: `authorization`, `token`, `password`, `pin`, `cardNumber`, `pan`, `track2`, dst — cek ulang daftar lengkap di `StandardLogSanitizer.kt` project asal saat implementasi, jangan sampai ada yang tertinggal).
- `StandardLogPublisher` — fire-and-forget publish via `BrokerClient` (disuntik dari `:app`, bukan modul global seperti `BrokerManager` project asal — lihat §6.1 soal wiring). Konstanta tetap: `EXCHANGE = "logs"`, `QUEUE = "logs.persist"`, `ROUTING_KEY = "logs.service"`.

### 6.1 Perbedaan desain dari project asal: tidak ada Prefs global

Project asal resolve `deviceId`/`merchantId`/HMAC-secret dari `Prefs` global (singleton, terisi lewat login). Project ini tidak punya padanan itu (lihat §1), dan `:common-core` tidak boleh depend balik ke `:provisioning-core` (circular, dilarang boundary). Karena itu `StandardLogPublisher.publish(...)` menerima `deviceId`/`merchantId`/`correlationId` sebagai **parameter eksplisit dari caller**, bukan resolve sendiri:

```kotlin
suspend fun StandardLogPublisher.publish(
    broker: BrokerClient,
    hmacSecret: String,
    correlationId: String,
    source: String,
    deviceId: String,
    merchantId: String,
    target: String,
    service: String,
    processDescription: String,
    jsonBody: Map<String, Any?> = emptyMap(),
    httpHeader: Map<String, Any?> = emptyMap(),
): Boolean
```

`hmacSecret` dan `broker` juga parameter eksplisit (bukan singleton ter-resolve sendiri) — caller (`:app`/`:feature-card-payment` lewat dependency injection yang sudah ada, `CardPaymentDependencies`) yang menyediakan. Ini lebih verbose dari project asal tapi sejalan dengan gaya `CardPaymentViewModel` yang sudah ada di project ini (constructor-injected `dependencies`, bukan object singleton global) dan menghindari `:common-core` diam-diam bergantung pada state yang di-set modul lain.

`correlationId` per transaksi: dibuat sekali di awal transaksi oleh caller (`CardPaymentViewModel`, mirip pola `idempotencyKey` yang sudah ada di sana sekarang) dan dipakai ulang untuk semua `publish()` call dalam transaksi yang sama — bukan `StandardLogCorrelation` singleton berbasis Prefs seperti project asal.

## 7. Kredensial broker & keamanan

Kredensial RabbitMQ (host, port, user, password, vhost, HMAC secret) **disediakan user secara out-of-band** (bukan ditulis di dokumen spec ini, lihat catatan di bawah) dan sudah diverifikasi manual bisa connect (TLS handshake + auth + exchange `logs` reachable, dicek pakai skrip Python `pika` sebelum brainstorming ini dimulai).

**Kenapa nilai literalnya tidak ditulis di file spec ini:** file spec ini commit ke git. Menulis kredensial plaintext di sini justru membuatnya *lebih* mudah ditemukan daripada di kode (yang setidaknya bakal di-obfuscate, lihat di bawah) — greppable langsung dari riwayat commit tanpa perlu decompile apa pun. Nilai literal akan disuntikkan langsung ke source `:broker-core` saat sesi implementasi (bukan lewat file yang di-commit terpisah), dan implementer harus:

1. **Tidak menaruh kredensial sebagai `String` literal polos** di manapun di source. Pecah/obfuscate (mis. split byte array + XOR dengan key yang juga di-split/dikonstruksi runtime, atau minimal Base64 dari byte array yang di-XOR — bukan cuma Base64 murni, itu bukan proteksi apa-apa) supaya tidak muncul sebagai string utuh yang bisa langsung ditemukan lewat `strings`/quick-scan Jadx pada APK release.
2. Decode kredensial **sekali** saat pertama dipakai (lazy, saat `:app` init broker), lalu **langsung** tulis ke `:secure-storage-core` (`EncryptedPrefs`) dan pakai instance ter-decrypt itu untuk pemakaian berikutnya — jangan simpan `String` plaintext hasil decode di memory lebih lama dari yang perlu.
3. Catat eksplisit di komentar kode (seperti komentar R2 di `EnvironmentConfig.kt` project asal) bahwa ini **bukan proteksi mutlak** — obfuscation menaikkan effort ekstraksi, tidak menghilangkan kemungkinannya sepenuhnya untuk penyerang dengan akses fisik+waktu ke APK release. Perbaikan jangka panjang (kredensial yang bisa di-rotate dari server, setara mekanisme `/internal/secret-key` project asal tapi dengan trigger provisioning-complete bukan login) dicatat sebagai **TODO out-of-scope**, lihat §8.

## 8. Di luar scope (dicatat, bukan diimplementasikan sekarang)

- Fetch kredensial dari endpoint `/internal/secret-key` (atau setara) yang di-trigger provisioning-complete — jalur "proper" jangka panjang, butuh device/terminal token dari `:provisioning-core` yang belum tentu tersedia untuk keperluan ini. Butuh brainstorming terpisah kalau/ketika mau dikerjakan.
- Rotasi kredensial.
- Implementasi `BrokerClient` untuk MQTT (payment-status, host lain) — kontrak `BrokerClient` di §5.1 sengaja disiapkan supaya ini tidak perlu ubah `StandardLogPublisher`/consumer lain, tapi implementasinya sendiri di luar scope dokumen ini.
- Perubahan versi `androidx.security:security-crypto`.

## 9. Titik integrasi `:feature-card-payment`

`CardPaymentDependencies` (interface yang sudah dipakai `CardPaymentViewModel`, lihat `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModel.kt`) dapat method baru, mis. `fun logTransaction(service: String, processDescription: String, jsonBody: Map<String, Any?>): Unit` yang di-wire ke `StandardLogPublisher.publish(...)` lewat implementasi konkret di `:app`. Titik panggil di `CardPaymentViewModel` (persis padanan `SoftposStandardLogger.log()` project asal):

| Event `CardPaymentViewModel` | `service` (padanan) | Kapan |
|---|---|---|
| `start()` diterima | `sale/sale_trx_start` | Sebelum `connectAndStart` |
| `CardTransactionEvent.Authorizing` | `sale/authorizing` | Di `onEvent` listener |
| `renderHostResult` sukses | `sale/sale_trx_success` | Setelah `hostResult` sukses & `authorization.approved` |
| `fail(message)` | `sale/sale_trx_failed` | Di titik `fail()` dipanggil (semua jalur) |

Detail persis payload (`jsonBody` apa saja yang masuk) diputuskan di implementation plan, bukan di sini — cukup titik integrasi & kontraknya yang perlu disepakati sebelum plan ditulis.

## 10. Testing

- `StandardLogHmacSigner`, `StandardLogSanitizer`, `StandardLogPayload` — unit test murni (deterministik, tanpa Android framework), pola sama seperti `RequestHeadersInterceptorTest`/`ApiResultTest` yang sudah ada di `:common-core`.
- `AmqpBrokerClient` — unit test lewat fake/mock `BrokerClient` di level consumer (`StandardLogPublisher`); `AmqpBrokerClient` sendiri di-exercise manual terhadap broker sungguhan (sudah diverifikasi lewat skrip `pika` terpisah, di luar test suite — tidak realistis unit-test koneksi TLS ke broker produksi di CI).
- `:secure-storage-core` — Robolectric wajib untuk apa pun yang menyentuh `EncryptedSharedPreferences`/`Base64`, ikuti peringatan yang sudah ada di `provisioning-core/build.gradle.kts` `testOptions` (return-default-values Android stub bisa diam-diam meloloskan test yang seharusnya gagal kalau tidak pakai Robolectric).
- `checkModuleBoundaries` (task gradle yang sudah ada) harus tetap hijau setelah `allowedProjectDeps` diupdate — jalankan `./gradlew checkModuleBoundaries` sebagai bagian dari verifikasi implementasi.
