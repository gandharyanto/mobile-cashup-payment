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

## 3a. Arsitektur seragam (Clean Architecture / ports & adapters)

Semua kode, termasuk yang dipindah dari tiga repo, disusun ulang ke satu pola. Hari ini polanya berbeda-beda: `edc-sdk` memakai manager singleton (`DeviceManager`, `SDKManager`), `anfu-bridge` memakai pembungkus tipis, modul payment memakai repository + Retrofit, dan `prepaid-lib` memakai helper per bank. Setelah ini hanya ada satu bentuk.

**Aturan ketergantungan: selalu ke dalam.** `api` → `data` → `domain` → `kernel`. Lapisan dalam tidak pernah mengenal lapisan luar.

| Lapisan | Isi | Boleh bergantung ke | Android / vendor |
|---|---|---|---|
| `kernel` | `Result`/`CashupError`, `Money`, `Clock`, port `Logger` | tidak ada | tidak |
| `<konteks>-domain` | entitas, use case, **port** (interface) | `kernel` | tidak (Kotlin/JVM murni) |
| `<konteks>-data` | implementasi port: HTTP, MQTT, signing, storage, adapter vendor | `<konteks>-domain`, `kernel` | ya |
| `api` | fasad publik, DTO publik, pemetaan error | domain semua konteks; merakit data lewat satu composition root | ya |
| `example-*` | aplikasi contoh | `api` saja | ya |

**Bounded context** (masing-masing punya sepasang `-domain` dan `-data`): `device` (reader, printer, scanner, key installer), `card` (sale CDCP), `qris`, `prepaid`, `provisioning`. Signing dan notification menjadi bagian `data` yang dipakai bersama lewat port di `kernel`/domain konteks terkait, bukan modul yang dilihat banyak konteks.

**Device, bentuk seragam untuk setiap vendor.** Port `CardReader`, `Printer`, `Scanner`, `KeyInstaller`, `DeviceInfo` ada di `device-domain`. Setiap keluarga perangkat punya satu modul `device-adapter-<vendor>` yang mengimplementasikannya, dibangun di atas modul vendor mentah (`vendor-*`) lewat satu bridge setipis `anfu-bridge`. Modul vendor mentah tidak terlihat di luar adapternya. Topwize dan semua vendor lain mengikuti bentuk ini, jadi "Topwize belum sama dengan ANFU" terselesaikan oleh aturan, bukan oleh satu perbaikan.

**Prepaid.** `prepaid-lib` dan `prepaid-core` adalah kode legacy (±134 file, helper per bank). Mereka tidak ditulis ulang di tahap ini. Dibungkus di `prepaid-data` sebagai adapter di belakang port `PrepaidCard` milik `prepaid-domain` (lapisan anti-korupsi): tipe lama tidak bocor ke atas, dan penulisan ulang per bank bisa dilakukan belakangan tanpa mengubah API.

**Konvensi seragam** (diperiksa CI/`checkModuleBoundaries` bila bisa):
- Penamaan modul `<konteks>-domain`, `<konteks>-data`, `device-adapter-<vendor>`, `vendor-<vendor>`.
- Operasi I/O adalah `suspend` atau mengembalikan `Flow`; tidak ada callback publik.
- Port tidak melempar exception: hasil berupa `Result<T, CashupError>`. Exception vendor diterjemahkan di batas adapter.
- Satu hierarki `CashupError` untuk semua konteks.
- Perakitan objek manual di satu composition root (`api`); tanpa framework DI di dalam SDK, supaya tidak memaksa partner memakai Hilt/Dagger/Koin.
- Domain tidak mengimpor `android.*`, `com.lib.*`, `com.anfu.*`, atau library jaringan; aturan ini diperiksa di build.

## 3b. Penyempurnaan arsitektur dan model integrasi

**Tingkatan kemampuan menggantikan dua model kaku.** §2 tetap berlaku sebagai dua *paket* yang paling umum, tetapi yang sebenarnya dipilih partner adalah tingkatan, dan boleh dikombinasikan bebas:

| Tingkat | Isi | Dipakai oleh |
|---|---|---|
| Device | detect, baca kartu, print, scan, key | semua |
| Capture | hasil baca kartu terenkripsi untuk host partner (`KeyMode.Partner`/`Cashup`) | Model 2 |
| Payments | provisioning, sale CDCP, QRIS | Model 1 |
| Prepaid | BRIZZI, TapCash, Flazz, E-Money (NFC/SAM) | tidak masuk Model 1 maupun 2; berdiri di atas Device |

Model 1 = Device + Payments. Model 2 = Device (+ Capture). Partner prepaid = Device + Prepaid. Perakitan satu pintu dan eksplisit: `CashupSdk.create(context, config, modules = listOf(DeviceModule(), PaymentsModule(), PrepaidModule()))`; modul yang tidak disebut tidak dimuat dan API-nya tidak ada.

**Perbaikan di akar `core`, bukan per bridge.** `DeviceManager.led` di `core` adalah `lateinit var`, dan empat modul mPOS (`anfu-mpos`, `newland-mpos`, `pax-mpos`, `topwise-mpos`) memanggil `DeviceManager.led.ledGreen(-1).start()` dan `DeviceManager.led.stopLed()` saat input PIN (diperiksa lewat indeks kode). Di perangkat tanpa modul vendor, keempatnya crash seperti ANFU. Karena source `core` menjadi milik project ini, `DeviceManager.led` dibuat tidak pernah kosong (nilai awal LED kosong), dan workaround di `anfu-bridge` (`NoLed`, `publishToDeviceManager`) dan `device-sdk-mpos` (`NoOpLedHelper`) dihapus. Tes kontrak (butir 5) menjaga agar vendor tidak kembali memakai `lateinit`. Catatan: `NoLed` di bridge hari ini hanya menimpa `connect`/`start`/`startThread`, tidak `stopLed`; versi di `core` harus menimpa semuanya.

**Matriks kemampuan per reader.** `reader.capabilities` (`printer`, `scanner`, `nfc`, `pinOnReader`, `sam`, dst.) ditentukan adapter. Partner tidak bercabang per vendor, dan UI menyembunyikan fitur yang tidak ada.

**Cakupan v1 hanya perangkat yang teruji.** Yang pernah dijalankan di perangkat: T6D, ANFU, Topwize. Ketiganya "didukung". Modul vendor lain dikompilasi tetapi ditandai eksperimental dan di luar jaminan API sampai diuji.

**Penyempurnaan teknis** (dikerjakan *setelah* semua modul berpindah dan tesnya hijau, karena butir 3 dan 4 mengubah perilaku alur kartu dan signing yang sudah jalan di perangkat):
1. Version catalog (`libs.versions.toml`) dan convention plugins di `build-logic/` (`cashup.android-library`, `cashup.kotlin-domain`, `cashup.vendor-adapter`). Aturan arsitektur §3a ditempel di plugin.
2. `explicitApi()` dan binary-compatibility-validator pada `api`; perubahan yang mematahkan API publik menggagalkan CI.
3. Transaksi sebagai state machine: `Flow<TransactionState>` (`WaitingCard → PinEntry → Processing → Approved/Declined/Failed`) plus versi `await()`. Callback EMV vendor hanya ada di dalam adapter.
4. `kotlinx.serialization` menggantikan Gson untuk DTO internal (Gson berbasis refleksi dan rapuh terhadap R8; bentuk JSON yang ditandatangani harus persis sama).
5. Tes kontrak per port: satu suite untuk `CardReader`/`Printer`/`KeyInstaller` yang harus lulus oleh *setiap* adapter, ditambah fake untuk tes domain.
6. UI sebagai artefak opsional `cashup-sdk-ui` (Compose), terpisah dari `device`/`payments`, sehingga partner Model 2 tidak membawa Compose.

Sengaja tidak dilakukan: Kotlin Multiplatform, framework DI (Hilt/Koin/Dagger) di dalam SDK, pemecahan modul lebih halus dari satu `-domain` + satu `-data` per konteks, dan penulisan ulang `prepaid-lib`.

## 4. Pemetaan source

Nama modul sasaran di tabel ini mengikuti §3a; sub-modul `payments/` pada pohon §3 menjadi pasangan `<konteks>-domain`/`<konteks>-data`.


| Dari | Ke | Catatan |
|---|---|---|
| `mobile-cashup-payment`: `common-core`, `signing-core`, `notification-core` | `sdk/payments` (dan `common` bila dipakai `device`) | pindah apa adanya |
| `device-sdk-api`, `reader-session`, `device-sdk-factory` | `sdk/device` | |
| `device-sdk-edcsdk`, `device-sdk-mpos` | `sdk/vendor` | |
| `provisioning-core`, `cdcp-core`, `qris-core` | `sdk/payments` | versi repo ini menjadi acuan (lihat §6) |
| `feature-card-payment`, `feature-qris` (UI) | `example-endtoend` | UI bukan bagian SDK; menjadi contoh pemakaian |
| `app` | `example-endtoend` | `AppContainer` menjadi contoh wiring |
| `anfu-bridge` | `sdk/vendor/anfu` | source menggantikan `anfu-bridge_1.0.0.jar` |
| `edc-sdk`, **hanya modul device**: `core`, `logger`, `other`, `blue-reader`, `pax`, `sunmi`, `centerm`, `nexgo`, `topwize`, `newland`, `szanfu`, `ingenico`, `anfu-mpos`, `newland-mpos`, `pax-mpos`, `topwise-mpos` | `sdk/vendor/*` | source menggantikan AAR hasil build; semuanya dibangun dari project ini |
| `edc-sdk`, **tidak diambil**: `common-general`, `cdcp-core`, `qris-core`, `prepaid-core`, `prepaid-lib`, `app`, `app-edc` | tetap di `edc-sdk` | seluruh logika payment datang dari `mobile-cashup-payment`; `edc-sdk` tidak dipakai untuk payment |

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

## 6. Pembagian sumber: device dari `edc-sdk`, payment dari `mobile-cashup-payment`, prepaid dari `mobile-prepaid-cashlez`

Keputusan:
- **Device**: dari `edc-sdk`, hanya modul device.
- **Payment** (provisioning, signing, cdcp, qris, notification): dari `mobile-cashup-payment`. `cdcp-core`, `qris-core` dan `common-general` milik `edc-sdk` tidak dibawa dan tidak dibandingkan.
- **Prepaid** (`prepaid-lib`, `prepaid-core`): dari `D:\gandha_cashup\projects\mobile-prepaid-cashlez`, bukan dari `edc-sdk`. **Sumbernya branch `feature/emoney-update-balance`** (commit `405fad31`, 2026-10-08), bukan `main`/`master`, karena keduanya belum terbaru. Branch itu belum digabung, jadi salinan dikunci ke commit tersebut dan dicatat di `NOTICE`/README project baru; perubahan berikutnya di branch itu tidak ikut otomatis. Yang dibawa hanya dua modul itu (106 + 28 file) plus `pc-command-1.0.jar`; modul vendor dan aplikasi di repo itu (±30 vendor lain, `app*`) tidak dibawa. Prepaid bergantung ke `core` dan `logger`; `logger` identik di kedua repo.

**`core` bercabang dan harus disatukan.** `mobile-prepaid-cashlez/core` dan `edc-sdk/core` memiliki 44 file source yang berbeda: `edc-sdk` punya `SDKManager`, `DukptKsn`, `DuktpVaultCompat`, `KcvUtil`, `device/*`, `CardMethod`, sedangkan versi prepaid punya aset EMV tambahan (`icTags.json`, parameter AID format baru) dan `DeviceManager`/`KeyManager`/`CardData` yang sudah berubah. Keputusan awal: **`core` dari `edc-sdk` menjadi satu-satunya `core`** (alur payment kita sudah memakainya dan teruji di perangkat), dan prepaid dikompilasi terhadapnya. Prepaid memakai `BaseRFCardHelper`, `BaseSamHelper`, `DeviceManager`, `ManagerException`, `Prefs`, `Network` dan fungsi ekstensi util dari `core`. Pemeriksaan lewat indeks kode: ke-22 simbol `core` yang dipakai prepaid ada di `core` `edc-sdk` (per nama, termasuk fungsi ekstensi util dan `Prefs`), jadi penyatuan kemungkinan besar bisa. Kecocokan tanda tangan fungsi baru terbukti saat kompilasi. Hal yang hanya ada di `core` versi prepaid dipindahkan ke `core` bersama (bukan dibiarkan dua versi). Ini tahap 3b di §8 dan berisiko, karena perubahan `core` menyentuh semua vendor.

Diperiksa di `build.gradle` `edc-sdk`: semua modul device hanya bergantung satu sama lain (`core`, `logger`, `other`, `emvlib`), tidak ke modul payment atau `common-general`. Jadi pemotongan ini tidak memutus dependensi.

**Semua AAR vendor dibangun dari project ini.** Karena modul vendor menjadi modul Gradle di satu build, dependensi antar-modul memakai `project(...)`. Itu juga menghilangkan masalah AGP yang menolak AAR bergantung langsung pada `.aar` lokal. Biner di `aarlib/` yang tersisa hanya yang memang tidak punya source di `edc-sdk`: `emvlib`, `drawDisplay` (dan `kozen`, bila perangkatnya dipakai), yang dipasang sebagai dependensi koordinat (bukan `files()`).

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
3. Masukkan modul device `edc-sdk` satu per satu (urutan: `logger`, `core`, `other`, lalu modul vendor); ganti AAR dengan modul.
3b. Masukkan `prepaid-lib`/`prepaid-core` dari `mobile-prepaid-cashlez`; satukan `core` (§6) dan kompilasi prepaid terhadapnya. Berhenti dan laporkan bila ada API `core` yang hilang atau berubah arti.
3c. Susun ulang semua modul ke pola §3a (`-domain`/`-data`/`device-adapter-*`), satu konteks per langkah, tes tetap hijau di tiap langkah. Prepaid dibungkus sebagai adapter di belakang port `PrepaidCard`.
4. Buat `topwize-bridge` setipis `anfu-bridge`, samakan perilaku dan daftar periksa §7.5; masuk sebagai `device-adapter-topwize`.
4b. Perbaiki `DeviceManager.led` di `core` (§3b) dan hapus workaround LED di bridge/`device-sdk-mpos`.
5. Bangun `sdk/api` (fasad, `KeyMode`, `CardCapture`, `PaymentResult`, `CashupError`, `reader.capabilities`, `CashupSdk.create(modules)`) di atas modul internal.
5b. Penyempurnaan teknis §3b butir 1-6, satu per satu, setelah tahap 6 hijau di perangkat.
6. `example-endtoend` dan `example-device`; jalankan di perangkat nyata (T6D, ANFU, Topwize).
7. Setelah Anda setuju: repo lama dirapikan menjadi app tipis yang memakai SDK. Repo lama tidak dihapus oleh saya.

## 9. Pertanyaan terbuka

- **Lisensi.** Boleh tidaknya mendistribusikan ulang kode/biner vendor (PAX, Sunmi, Centerm, Nexgo, Topwize, Newland, Topwise, ANFU, Ingenico) ke partner eksternal. Harus dipastikan dengan tiap vendor sebelum rilis eksternal; tidak mempengaruhi tahap 0-6.
- **Akses `edc-sdk`.** Repo `edc-sdk` ada di Bitbucket `cashlez-vapn`; saya perlu memastikan boleh disalin, dan sesuai catatan `session`, kunci SSH ke Bitbucket belum jalan di mesin ini (tidak mempengaruhi menyalin dari folder lokal).
- **Riwayat git.** Default: salin tanpa riwayat, repo lama tetap menyimpannya. Bila riwayat harus ikut, diperlukan `git subtree`/`filter-repo` per modul.
- **Ukuran.** Karena hanya modul device yang dibawa dan AAR-nya dibangun dari source, biner lama (`*-debug`, versi 1.0.61-1.0.63) tidak dibawa. Ukuran repo baru tinggal source ditambah beberapa biner tanpa source; Git LFS kemungkinan tidak perlu.
- **Biner tanpa source.** `emvlib`, `drawDisplay` dan `kozen` tidak punya modul di `edc-sdk`; perlu dipastikan sumbernya (repo mana) dan lisensinya. Modul `other` bergantung ke `emvlib`.
- **Repositori Gradle.** `edc-sdk` memakai `jcenter()`, `mavenLocal` di path pribadi, dan `jitpack`; di project baru dibersihkan.
