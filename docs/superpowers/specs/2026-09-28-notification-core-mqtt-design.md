# `:notification-core` — MQTT Payment-Status Listener — Design Spec

**Tanggal:** 2026-09-28
**Status:** disetujui, siap dijadikan implementation plan
**Cakupan:** modul baru yang connect ke broker MQTT dan mengekspos pola "tunggu push N detik, fallback REST status-check sekali kalau timeout, koneksi MQTT tetap hidup sesudahnya" untuk status pembayaran. **Tidak** termasuk: wiring ke UI/ViewModel manapun (card payment atau QRIS), implementasi REST status-check API (belum ada di backend), atau payload/topic schema final dari backend.

---

## 1. Latar belakang

`mobile-cashup-payment` belum punya modul MQTT atau notification apa pun — baru rencana di `docs/EDC_PAYMENT_APP_DESIGN.md` (QRIS Dynamic: generate QR → dengarkan push MQTT topic unified → fallback poll status-check kalau >5 detik tanpa push). Modul QRIS itu sendiri belum ada di repo; `:feature-card-payment` yang sudah ada saat ini sepenuhnya sinkron (`CardPaymentDependencies.authorize()` langsung mengembalikan `SaleResponse` dari host, lihat `feature-card-payment/src/main/kotlin/com/cashup/feature/cardpayment/CardPaymentViewModel.kt`).

User memverifikasi manual konektivitas broker dev pada 2026-09-28 (payment → message diterima 516ms via Tailscale, 212ms via LAN office, keduanya diukur di port `8883`/TLS). Detail broker dev:

| network | host | port terverifikasi | catatan |
|---|---|---|---|
| Tailscale | `100.114.27.64` | `8883` (TLS) | proven 2026-09-28, 516 ms |
| office LAN | `192.168.90.46` | `8883` (TLS) | proven 2026-09-28, 212 ms |

Broker juga listen di `1883` (plain, tanpa TLS) dan `8083`/`8084` (WebSocket). Autentikasi MQTT-nya sendiri **anonymous di kedua port** — broker dev tidak minta username/password maupun client certificate; bedanya port 8883 vs 1883 murni ada/tidaknya enkripsi transport (TLS), bukan credential.

**Keputusan eksplisit (instruksi user, sesi ini):** implementasi memakai **port `1883`, plain, tanpa TLS** — menyimpang dari rekomendasi dokumen konektivitas yang menandai `8883` sebagai satu-satunya port yang harus dipakai dan eksplisit bilang "don't use" `1883`. Ini dicatat di sini karena:
- Latency yang sudah diverifikasi (516ms/212ms) diukur di `8883`, **bukan** `1883` — belum ada bukti latency/reliability yang sama untuk plain MQTT. Perlu smoke-test ulang saat implementasi.
- Traffic topic `cashup/staging/v1/devices/<deviceId>` (status pembayaran) akan lewat jaringan tanpa enkripsi transport di path ini. Untuk broker dev dengan CA test yang sudah disiapkan khusus, ini dianggap dapat diterima oleh user; **produksi wajib dievaluasi ulang** sebelum dipakai sungguhan (lihat §8).

## 2. Modul & dependency graph

Satu modul baru, tier "kontrak murni" (`emptySet()` di `gradle/module-boundaries.gradle.kts`, sejajar `:common-core`/`:device-sdk-api`/`:signing-core`):

- **`:notification-core`** — client MQTT generik (subscribe-only) + policy "tunggu/fallback/tetap-hidup". `kotlin("jvm")` murni, sama seperti `:common-core`/`:cdcp-core` — **subset Java 8 / API 23**, tanpa `java.time`, tanpa `java.util.Base64`, tanpa API yang butuh core library desugaring (lihat peringatan di `common-core/build.gradle.kts` — repo ini sudah 3x kena bug kelas ini).

`:app` dependency bertambah ke `:notification-core` (pemilik lifecycle koneksi — connect saat startup, mirror pola `AppContainer` yang sudah ada). **Tidak** ada modul UI/feature lain yang bergantung ke `:notification-core` di spec ini — wiring ke `:feature-card-payment` atau modul QRIS masa depan di luar scope (lihat §8), supaya modul ini bisa dibangun dan diuji berdiri sendiri dulu.

`gradle/module-boundaries.gradle.kts` — perubahan ke `allowedProjectDeps`:

```kotlin
":notification-core" to emptySet(),

":app" to setOf(
    ":provisioning-core", ":cdcp-core", ":device-sdk-edcsdk", ":device-sdk-factory",
    ":feature-card-payment", ":notification-core",                    // tambah :notification-core
),
```

### 2.1 Relasi dengan spec `:broker-core` (2026-09-23)

`docs/superpowers/specs/2026-09-23-standard-log-broker-design.md` §5.1 mendefinisikan `BrokerClient` sebagai kontrak **publish-only** (`connect()`, `publish(exchange, routingKey, payload)`, `disconnect()`) untuk AMQP, dengan catatan "implementasi MQTT bisa ditambah nanti tanpa ubah consumer". Spec ini **sengaja tidak** mengimplementasikan `BrokerClient` untuk MQTT, karena arahnya berlawanan: `BrokerClient` itu producer (app → broker, fire-and-forget log), sedangkan kebutuhan di sini adalah consumer (broker → app, dengarkan status push). Memaksakan satu interface untuk dua arah data yang berbeda akan membuat keduanya lebih buruk. `:notification-core` dan `:broker-core` (kalau/ketika `:broker-core` diimplementasikan) tetap boleh hidup berdampingan sebagai dua modul transport terpisah, masing-masing untuk satu tujuan.

## 3. Kontrak inti

```kotlin
package com.cashup.notification

/** Pesan mentah dari broker — payload TIDAK di-parse di sini karena schema JSON dari
 *  backend belum final (lihat §8). Konsumen (mis. PaymentStatusWaiter) yang tahu cara
 *  mem-parsing & mencocokkannya. */
data class RawNotification(val topic: String, val payload: String)

interface PaymentNotificationTransport {
    val isConnected: Boolean

    /** Subscribe ke `cashup/staging/v1/devices/<deviceId>`, QoS 1. Idempotent kalau
     *  dipanggil ulang dengan deviceId yang sama saat sudah connected. */
    fun connect(deviceId: String)

    /** Hot stream — semua subscriber melihat pesan yang sama, tidak ada replay. */
    val messages: Flow<RawNotification>

    fun disconnect()
}
```

```kotlin
package com.cashup.notification

/** Kebijakan "tunggu push N detik, fallback REST sekali kalau timeout, transport TIDAK
 *  disconnect setelah timeout" — independen dari detail MQTT, testable dengan fake
 *  transport (lihat §6). */
class PaymentStatusWaiter(private val transport: PaymentNotificationTransport) {

    /**
     * @param matches dipanggil untuk tiap [RawNotification] yang masuk selama window
     *   menunggu; kembalikan hasil ter-parse kalau pesan ini yang dicari, `null` kalau
     *   bukan (mis. status device lain / invoice lain di topic yang sama).
     * @param statusCheck dipanggil TEPAT SEKALI kalau tidak ada [matches] yang berhasil
     *   dalam [timeoutMs]. Implementasi REST-nya disuntik caller — belum ada di repo ini.
     * @return hasil dari [matches] (kalau push datang duluan) atau dari [statusCheck]
     *   (kalau timeout), termasuk `null` kalau keduanya tidak menemukan apa-apa.
     */
    suspend fun <T> await(
        timeoutMs: Long = 5_000L,
        matches: (RawNotification) -> T?,
        statusCheck: suspend () -> T?,
    ): T?
}
```

Implementasi `await`: `race` antara `transport.messages.mapNotNull(matches).first()` dan `delay(timeoutMs)` (mis. lewat `select` coroutine atau `withTimeoutOrNull`). Kalau delay menang, panggil `statusCheck()` sekali dan kembalikan hasilnya — **tidak** memanggil `transport.disconnect()` di jalur manapun; disconnect murni tanggung jawab pemilik lifecycle (`:app`, lihat §5).

Generik `<T>` sengaja dipakai (bukan `PaymentStatusMessage` tetap dengan field `invoiceId` seperti sketsa awal di percakapan) karena schema payload backend belum final — lihat §8. Caller yang membangun `matches`/`statusCheck` yang menentukan tipe hasil apa yang mereka butuhkan (mis. status transaksi, atau raw JSON kalau schema belum ada parser-nya).

## 4. `MqttNotificationTransport` (implementasi)

- Library: **Eclipse Paho Java MQTT client** (`org.eclipse.paho:org.eclipse.paho.client.mqttv3`), bukan HiveMQ MQTT Client. Alasan: Paho murni Java lama (pre-Java 8), tidak menyentuh `java.time`/API 26+, proven dipakai langsung di Android API level rendah tanpa wrapper `Android Service` yang sudah deprecated. HiveMQ client lebih modern tapi belum diverifikasi bebas dari API di atas subset yang diizinkan modul ini — mengulang jebakan yang sama yang sudah 3x menggigit repo ini (komentar di `common-core/build.gradle.kts`) bukan risiko yang perlu diambil untuk keputusan library yang gampang diganti nanti kalau perlu.
- Koneksi: `tcp://<host>:<port>` (bukan `ssl://`, sesuai §1), `MqttConnectOptions` tanpa `setUserName`/`setPassword`/`setSocketFactory` (anonymous, tanpa TLS), `cleanSession = true` (topic per-device, tidak butuh queued message lintas sesi untuk versi ini), `automaticReconnect = true`.
- `clientId`: `"edc-$deviceId"` — sesuai dokumen konektivitas ("itu id yang akan dibutuhkan planned login, jadi pakai sekarang menghindari perubahan nanti").
- Topic: `cashup/staging/v1/devices/$deviceId`, QoS 1.
- Callback Paho (`MqttCallback.messageArrived`, thread non-coroutine milik Paho) di-bridge ke `messages: Flow<RawNotification>` lewat `callbackFlow` — `trySend` di `messageArrived`, `awaitClose { client.disconnect() }` hanya kalau ini satu-satunya collector aktif (lihat §5 soal siapa yang benar-benar connect/disconnect).
- Reconnect: serahkan ke `automaticReconnect` bawaan Paho (exponential backoff bawaan library, 1s–128s) — tidak reimplementasi backoff manual.

## 5. Lifecycle & config

Dipegang satu instance oleh `:app` (mirror pola `AppContainer` yang sudah ada untuk `SaleRepository`/`ProvisioningRepository` dkk.) — `connect(deviceId)` dipanggil sekali saat device sudah terprovisioning (deviceId, serial dari `StoredDeviceSigner`, tersedia). `:notification-core` sendiri **tidak** depend ke `:provisioning-core` — `deviceId` diterima sebagai parameter dari caller (`:app`), sama seperti pola `SaleRepository.sale(deviceId, ...)` yang sudah ada. Tetap hidup selama proses app berjalan; tidak ada `disconnect()` eksplisit dari alur pembayaran manapun.

Config baru di `app/src/main/kotlin/com/cashup/app/AppConfig.kt` (SharedPreferences biasa, pola identik `baseUrl` yang sudah ada di file yang sama):

```kotlin
var mqttHost: String
    get() = prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST
    set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

var mqttPort: Int
    get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
    set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

private companion object {
    // ...KEY_BASE_URL/DEFAULT_BASE_URL yang sudah ada...
    const val KEY_MQTT_HOST = "mqtt_host"
    const val KEY_MQTT_PORT = "mqtt_port"
//  const val DEFAULT_MQTT_HOST = "192.168.90.46" // office LAN
    const val DEFAULT_MQTT_HOST = "100.114.27.64" // tailscale
    const val DEFAULT_MQTT_PORT = 1883
}
```

## 6. Error handling

- Koneksi gagal / drop: `automaticReconnect` Paho menangani retry; `messages` Flow tidak mengeluarkan error untuk drop transient — collector cukup terus menunggu, pesan berikutnya muncul setelah reconnect berhasil.
- Pesan dengan payload yang gagal di-parse oleh `matches` (bukan JSON valid, dsb.): tanggung jawab `matches` sendiri untuk mengembalikan `null` (skip), **bukan** melempar exception yang membatalkan `await()` — didokumentasikan di KDoc §3, ditegakkan lewat test (§7).
- `statusCheck()` melempar exception: **tidak ditangkap** oleh `PaymentStatusWaiter` — exception dari `statusCheck` propagate langsung ke caller `await()`. Caller yang memutuskan retry/fallback lanjutan, bukan modul ini (YAGNI — belum ada kebutuhan konkret untuk kebijakan retry di layer ini).

## 7. Testing

Unit test JVM murni untuk `PaymentStatusWaiter` pakai fake `PaymentNotificationTransport` (backing `MutableSharedFlow`, tanpa broker sungguhan):

- Pesan yang cocok (`matches` mengembalikan non-null) datang sebelum timeout → `await()` mengembalikan hasil itu, `statusCheck` **tidak** dipanggil.
- Tidak ada pesan cocok dalam `timeoutMs` → `statusCheck()` dipanggil **tepat sekali**, hasilnya yang dikembalikan.
- Pesan yang tidak cocok (`matches` mengembalikan `null`, mis. invoice/topic lain) tidak menghentikan menunggu — window tetap jalan sampai timeout atau pesan yang cocok datang.
- Setelah timeout & fallback selesai, `transport.disconnect()` **tidak pernah dipanggil** oleh `PaymentStatusWaiter` (assert lewat fake yang mencatat pemanggilan).

`MqttNotificationTransport` sendiri (wrapper Paho) di-exercise manual terhadap broker dev (`100.114.27.64:1883`) saat implementasi — sama seperti `AmqpBrokerClient` di spec `:broker-core` §10, tidak realistis unit-test koneksi TCP sungguhan di CI. Smoke-test manual ini juga kesempatan memverifikasi ulang latency di port `1883` (lihat §1 — belum ada data untuk port ini).

`./gradlew checkModuleBoundaries` harus tetap hijau setelah `allowedProjectDeps` diupdate.

## 8. Di luar scope (dicatat, bukan diimplementasikan sekarang)

- Wiring ke `:feature-card-payment` (`CardPaymentViewModel`/`CardPaymentDependencies`) atau ke modul QRIS (belum ada di repo). Titik integrasi & kontrak pemanggilan diputuskan terpisah, setelah `:notification-core` berdiri sendiri dan teruji.
- Implementasi REST status-check API (`statusCheck: suspend () -> T?` di `PaymentStatusWaiter.await` murni parameter — belum ada endpoint konkret di `:cdcp-core` atau modul lain).
- Schema payload JSON dari topic `cashup/staging/v1/devices/<deviceId>` — belum ada kontrak resmi/lengkap dari backend (tipe event apa saja yang mungkin, apakah semua topic device cuma QRIS atau juga CDCP nantinya, versioning `v` dipakai untuk apa). Satu contoh nyata event yang sudah dilihat (2026-09-28):

  ```json
  {
    "v": 1,
    "id": "e15d7bd9-c38f-4240-99a3-16fe4ea1bcaa",
    "type": "qris.payment_paid",
    "module": "qris",
    "data": {
      "amount": "15000.00",
      "currency": "IDR",
      "reference": "227895640297",
      "invoiceNumber": "000001",
      "transactionId": "01a0d6ea-37b8-748d-8e6f-3a3f19b32737"
    },
    "createdAt": "2026-09-25T04:54:29.575910040Z"
  }
  ```

  Envelope-nya (`v`/`id`/`type`/`module`/`data`/`createdAt`) cukup masuk akal untuk dipakai sebagai dasar `matches` (mis. cocokkan `type == "qris.payment_paid"` dan `data.invoiceNumber`/`data.transactionId` terhadap invoice yang ditunggu) — tapi karena baru satu contoh yang dikonfirmasi, `RawNotification.payload` tetap sengaja `String` mentah di kontrak `:notification-core` (§3), bukan data class ter-parse. Parsing envelope ini jadi tanggung jawab `matches` yang disuntik caller (`:feature-card-payment`/QRIS, saat wiring-nya dikerjakan — §8 baris pertama), supaya `:notification-core` tidak diam-diam mengunci kontrak yang belum resmi dari backend. Implementer boleh pakai contoh di atas sebagai starting point saat itu.
- TLS (port `8883`) untuk produksi — port `1883` di spec ini eksplisit untuk dev broker saat ini (lihat §1). Evaluasi ulang wajib sebelum rilis produksi: pindah ke `8883`, bundling CA "CashUP TR-34 Test Root CA" (atau CA produksi), `MqttConnectOptions.setSocketFactory` dengan hostname verification aktif.
- Autentikasi MQTT (username/password/client cert) — broker dev saat ini anonymous; kalau broker produksi mewajibkan credential, `MqttConnectOptions` perlu field tambahan (di luar scope perubahan `PaymentNotificationTransport` itu sendiri, cukup detail implementasi `MqttNotificationTransport`).
- Implementasi `BrokerClient` (spec `:broker-core`) untuk MQTT — lihat §2.1, sengaja tidak digabung.
