# Cashup Payment SDK: satu sumber untuk payment dan device

Tanggal: 2026-10-20. Status: draf untuk review.

## 1. Tujuan

Hari ini kode payment dan device tersebar di tiga repo (`mobile-cashup-payment`, `anfu-bridge`, `edc-sdk`) dan baru dua perangkat yang jalan (ANFU, Topwize), dengan Topwize belum selevel ANFU. Tujuannya satu project library, `cashup-payment-sdk`, berisi seluruh source payment dan device, plus aplikasi contoh. Hanya project ini yang dipelihara; aplikasi pengguna memasang satu SDK.

Pengguna SDK adalah **partner/merchant eksternal**, bukan hanya app internal Cashup. Itu menaikkan standar: API publik stabil, tanpa tipe vendor, tanpa data kartu mentah, tanpa konfigurasi internal.

## 2. Dua model integrasi

| | Model 1: end-to-end | Model 2: à la carte |
|---|---|---|
| Alur | init → detect device → sale CDCP/QRIS → transaksi selesai → hasil ke app | init → detect device → baca kartu / print / scan → data ke app |
| Sale | SDK mengurus (backend Cashup) | app partner mengurus sendiri |
| Modul yang dibutuhkan | `device` + `payments` | `device` saja |
| Key DUKPT | `KeyMode.Cashup` (wajib) | `KeyMode.Cashup` atau `KeyMode.Partner` |
| Data kartu ke app | tidak ada, hanya `PaymentResult` | `CardCapture`: terenkripsi DUKPT + KSN, PAN ter-mask |

`KeyMode.Partner`: partner memberi IPEK/KSN dari host-nya; SDK tidak menyentuh backend Cashup. Key partner dan key Cashup disimpan di slot terpisah dan tidak boleh dicampur dalam satu transaksi.

## 3. Struktur project

```
cashup-payment-sdk/
  sdk/
    api/            -- kontrak publik (CashupSdk, CardCapture, PaymentResult, error, event). Satu-satunya yang terlihat partner.
    device/         -- kontrak device (CardReader, Printer, Scanner, KeyInstaller, DeviceInfo) + reader-session
    payments/       -- signing, provisioning, cdcp, qris, notification
    vendor/         -- satu modul per keluarga perangkat (lihat §4)
  example-endtoend/ -- contoh Model 1
  example-device/   -- contoh Model 2 (tanpa modul payments)
  aarlib/           -- hanya biner vendor yang tidak punya source di repo ini
  gradle/module-boundaries.gradle.kts
```

Artefak: `cashup-sdk-device`, `cashup-sdk-payments` (bergantung ke device), dan `cashup-sdk` (BOM yang memuat keduanya). Model 2 cukup `cashup-sdk-device`.

Aturan batas, diperiksa `checkModuleBoundaries` seperti sekarang: modul vendor hanya boleh melihat kontrak `device`; `payments` tidak boleh melihat modul vendor; aplikasi contoh hanya boleh bergantung ke `api`/`device`/`payments`.

## 4. Pemetaan source

| Dari | Ke | Catatan |
|---|---|---|
| `mobile-cashup-payment`: `common-core`, `signing-core`, `notification-core` | `sdk/payments` (dan `common` bila dipakai `device`) | pindah apa adanya |
| `device-sdk-api`, `reader-session`, `device-sdk-factory` | `sdk/device` | |
| `device-sdk-edcsdk`, `device-sdk-mpos` | `sdk/vendor` | |
| `provisioning-core`, `cdcp-core`, `qris-core` | `sdk/payments` | versi repo ini menjadi acuan (lihat §6) |
| `feature-card-payment`, `feature-qris` (UI) | `example-endtoend` | UI bukan bagian SDK; menjadi contoh pemakaian |
| `app` | `example-endtoend` | `AppContainer` menjadi contoh wiring |
| `anfu-bridge` | `sdk/vendor/anfu` | source menggantikan `anfu-bridge_1.0.0.jar` |
| `edc-sdk`: `core`, `logger`, `common-general`, modul vendor (`pax`, `sunmi`, `centerm`, `nexgo`, `topwize`, `newland`, `szanfu`, `ingenico`, `other`, `blue-reader`, `*-mpos`, `anfu-mpos`) | `sdk/vendor/*` | source menggantikan AAR hasil build |
| `edc-sdk`: `app`, `app-edc` | dibuang atau menjadi contoh | aplikasi, bukan library |

## 5. API publik (`sdk/api`)

```kotlin
CashupSdk.init(context, CashupConfig(environment, keyMode, merchant...))  // HTTPS wajib
sdk.device.detect(): List<Reader>            // T6D bawaan + mPOS Bluetooth, satu tipe
sdk.device.connect(reader) / events: Flow<DeviceEvent>
sdk.device.readCard(amount): CardCapture     // Model 2
sdk.device.print(receipt) / scan()
sdk.payments.card(amount): PaymentResult     // Model 1; hanya ada bila modul payments disertakan
sdk.payments.qris(amount): QrisSession
sdk.provisioning.status() / activate(...)
```

Aturan: tidak ada tipe `com.lib.*`, `com.anfu.*`, `com.cz.*` di API; tidak ada `CardTransactionData` mentah; semua gagal sebagai `CashupError` bertipe (bukan exception vendor). `sdk.payments` tidak ada di API bila modul tidak disertakan, bukan error saat runtime.

## 6. Bagian yang perlu direkonsiliasi

`edc-sdk` sudah punya `cdcp-core`, `qris-core`, `prepaid-core`, dan `prepaid-lib` yang tumpang tindih dengan modul repo ini. Aturan awal: **versi dari `mobile-cashup-payment` menjadi acuan** (sudah dipakai di app, punya signing, DUKPT, dan tes). Sebelum membuang versi `edc-sdk`, bandingkan dan pindahkan hal yang hanya ada di sana. `prepaid-*` belum dibahas dengan Anda; tidak dipindah sampai ada keputusan.

## 7. Syarat keamanan dan rilis

1. Model 1: data kartu tidak pernah keluar dari SDK. Model 2: hanya keluaran terenkripsi (§2).
2. Tidak ada biner vendor `*-debug-*` pada rilis (mencetak frame mentah dan serial reader). Build rilis memakai source yang dikompilasi sendiri atau biner `release`.
3. Tidak ada nilai hardcoded: serial perangkat (`HARDCODED_SERIAL_NUMBER` di `AppContainer`), URL LAN kantor, host `http://` cleartext, host MQTT.
4. Rilis lewat Maven privat berautentikasi, semver, pemeriksaan kompatibilitas biner API di CI, R8/obfuscation untuk semua selain `api`.
5. Dua workaround ANFU (LED kosong, PIK ikut disimpan di vault lokal) menjadi daftar periksa wajib untuk setiap bridge vendor baru, termasuk Topwize.

## 8. Tahapan (tiap tahap harus build dan lolos tes sebelum lanjut)

0. Buat `cashup-payment-sdk` (repo git baru), kerangka Gradle, `checkModuleBoundaries`.
1. Salin modul `mobile-cashup-payment` (§4) dan jalankan seluruh tes yang ada. Repo lama tidak diubah.
2. Masukkan source `anfu-bridge` sebagai `sdk/vendor/anfu`; ganti jar dengan modul.
3. Masukkan modul vendor `edc-sdk` satu per satu; ganti AAR dengan modul; rekonsiliasi duplikat (§6).
4. Buat `topwize-bridge` setipis `anfu-bridge`, samakan perilaku dan daftar periksa §7.5.
5. Bangun `sdk/api` (fasad, `KeyMode`, `CardCapture`, `PaymentResult`, `CashupError`) di atas modul internal.
6. `example-endtoend` dan `example-device`; jalankan di perangkat nyata (T6D, ANFU, Topwize).
7. Setelah Anda setuju: repo lama dirapikan menjadi app tipis yang memakai SDK. Repo lama tidak dihapus oleh saya.

## 9. Pertanyaan terbuka

- **Lisensi.** Boleh tidaknya mendistribusikan ulang kode/biner vendor (PAX, Sunmi, Centerm, Nexgo, Topwize, Newland, Topwise, ANFU, Ingenico) ke partner eksternal. Harus dipastikan dengan tiap vendor sebelum rilis eksternal; tidak mempengaruhi tahap 0-6.
- **Akses `edc-sdk`.** Repo `edc-sdk` ada di Bitbucket `cashlez-vapn`; saya perlu memastikan boleh disalin, dan sesuai catatan `session`, kunci SSH ke Bitbucket belum jalan di mesin ini (tidak mempengaruhi menyalin dari folder lokal).
- **Riwayat git.** Default: salin tanpa riwayat, repo lama tetap menyimpannya. Bila riwayat harus ikut, diperlukan `git subtree`/`filter-repo` per modul.
- **Ukuran.** `edc-sdk` ±190 MB; biner `.aar` lama (`*-debug`, versi 1.0.61-1.0.63) tidak dibawa. Perlu keputusan Git LFS atau tidak.
- **`prepaid-*`.** Masuk SDK atau tidak.
- **Repositori Gradle.** `edc-sdk` memakai `jcenter()`, `mavenLocal` di path pribadi, dan `jitpack`; di project baru dibersihkan.
