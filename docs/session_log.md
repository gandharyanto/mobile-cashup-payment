# Session Log

## Status terkini — 2026-09-22 (update 5, online PIN DUKPT Topwise berhasil)

Commit UI/transaksi modular `15e1baa` sudah dipush ke
`origin/feat/provisioning-edc-mobile-adoption`. Sesudah commit itu, jalur online PIN Topwise
diperbaiki lintas repo.

Perubahan di repo sibling `edc-sdk` (belum dicatat di bagian riwayat lama):

- `CardData` membawa `pinKsn` untuk menandai bahwa PIN block sudah dienkripsi DUKPT hardware.
- Topwise `PinParam` memakai `systemKey.keyIndex` dan `KEYTYPE_DUKPT_DES` untuk online PIN,
  bukan slot static `PEK`.
- KSN dinaikkan sebelum PIN entry dan divalidasi sepanjang 10 byte.
- Pemanggilan `KeyManager.decrypt(PIN_KEY)` dihapus; clear PIN block tidak pernah masuk aplikasi.
- Logging PAN, offline PIN block, KEK, PIK, dan IPEK pada modul Topwise dihapus/redaksi.
- Perubahan lokal yang sudah ada di modul `other` pada repo sibling tidak disentuh.

Perubahan lanjutan di project ini:

- `CardTransactionData` menerima `pinKsn`.
- `EmvGateway` membaca encrypted PIN block dan KSN dari `edc-sdk`.
- `CardPayloadFactory` meneruskan PIN block hardware tanpa enkripsi ulang dan menurunkan
  `pinKsnIndex` dari KSN hardware. Reader lama tanpa `pinKsn` tetap memakai fallback enkripsi
  software agar vendor lain kompatibel.
- ICC sekarang memakai purpose provisioning `EMV`; test provisioning dan payload diselaraskan
  dengan empat purpose `TRACK`, `AMOUNT`, `PIN`, `EMV`.

Verifikasi unit/build berhasil:

```text
gradlew.bat :provisioning-core:testDebugUnitTest :cdcp-core:testDebugUnitTest \
  :device-sdk-edcsdk:testDebugUnitTest :feature-card-payment:testDebugUnitTest \
  :app:assembleDebug checkModuleBoundaries
BUILD SUCCESSFUL (179 tasks)

edc-sdk: gradlew.bat :core:assembleRelease :topwize:assembleRelease
BUILD SUCCESSFUL (79 tasks)

gradlew.bat build
BUILD SUCCESSFUL (658 tasks; debug/release, unit tests, lint, dan module boundaries)
```

Uji terminal fisik online PIN pukul `16:12` juga berhasil melewati titik lama:

- Tidak ada `PIK tidak ditemukan`, uncaught Binder exception, atau crash.
- `EMV_CardHolderVerify = 0` dan proses lanjut ke online authorization.
- Request memiliki `pinblockEnc` serta `pinKsnIndex=00004`; counter PIN hardware terpisah dari
  counter data `0000D`, sesuai kontrak DUKPT per-purpose.
- Host membalas `503 ACQUIRER_HOST_UNAVAILABLE` dengan `deliveryState=NOT_SENT`. Ini kegagalan
  eksternal acquirer, bukan kegagalan PIN/EMV/payload.

AAR lokal yang dipakai project ini sudah diganti dengan hasil build tersebut, tetapi `aarlib/*.aar`
memang di-ignore Git. Reproduksi dari clone baru memerlukan build/publikasi artefak `edc-sdk` yang
memuat patch di atas.

## Status terkini — 2026-09-22 (update 4, handoff Binder/PIK)

Permintaan terakhir user: lanjutkan integrasi transaksi EDC dengan UI **persis** seperti
`mobile-apps-cashlez/app-v3`, memakai key DUKPT hasil provisioning. Sesi dihentikan atas
permintaan user setelah investigasi transaksi fisik di Topwise
`HI14593000255`; lanjutkan dari poin di bawah, jangan mengulang riset UI.

### UI dan build yang sudah selesai

- `feature-card-payment` sekarang memiliki dua layar terpisah sesuai app-v3:
  simple calculator dari `feature/pos/activity_calculator.xml`, lalu waiting-card dari
  `app-v3/activity_payment_with_credit_debit.xml` setelah tombol `OK`.
- Asset, font, warna, ukuran utama, teks, background, logo, ikon, dan GIF diambil dari
  app-v3. Calculator logic berada di `SimpleCalculator` dan sudah memiliki unit test.
- Implementasi sale lama di `:app` dihapus; feature baru tetap modular dan menjadi satu-satunya
  pemilik state/UI transaksi kartu.
- Verifikasi terakhir sebelum handoff:

```text
gradlew.bat :device-sdk-edcsdk:testDebugUnitTest :feature-card-payment:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL (168 tasks)
```

APK hasil build tersebut sudah dipasang ke terminal fisik dengan `adb install -r`.

### Masalah Binder pertama — SUDAH diperbaiki

Log lama pukul `14:54:16` menunjukkan loop callback re-entrant:

```text
PinInputListener.onError
 -> EdcSdkCardReader.onError
 -> RealEmvGateway.stop
 -> EmvConfiguration.stopEmv / stopGetPin
 -> PinInputListener.onError (berulang)
 -> android.os.DeadObjectException
```

Penyebabnya: `gateway.stop()` dipanggil sebelum flag `completed` dikunci. `stopEmv()` milik
Topwise memanggil `onError` lagi secara sinkron, sehingga service Binder vendor akhirnya mati.

Perbaikan **belum di-commit** ada di
`device-sdk-edcsdk/.../EdcSdkCardReader.kt`: completion sekarang melakukan CAS terlebih dahulu,
baru cleanup gateway; cancellation juga mengunci completion sebelum `stop()`. Ditambahkan test
regresi `EdcSdkCardReaderTest` dengan fake gateway yang memanggil `onError` kembali dari `stop()`;
test membuktikan `stop()` hanya dipanggil sekali. Jangan revert perbaikan ini.

### Masalah Binder kedua — BELUM selesai, akar masalah sudah pasti

Pada build terbaru proses aplikasi tetap hidup (`pid 7625`) dan tidak ada `FATAL EXCEPTION` atau
entry `data_app_crash`. Potongan user `Binder.execTransact(Binder.java:1244)` berasal dari exception
callback Binder pinpad, tetapi baris penting tepat di atasnya adalah:

```text
09-22 14:59:38.619 E/JavaBinder: *** Uncaught remote exception!
09-22 14:59:38.619 E/JavaBinder: java.lang.Exception: PIK tidak ditemukan
09-22 14:59:38.619 E/JavaBinder:   at com.lib.core.KeyManager.getKey(...)
09-22 14:59:38.619 E/JavaBinder:   at com.lib.core.KeyManager.decrypt(...)
09-22 14:59:38.619 E/JavaBinder:   at ...topwize.emv.EmvConfiguration...onConfirmInput(EmvConfiguration.kt:238)
09-22 14:59:38.619 E/JavaBinder:   at ...GetPinListener$Stub.onTransact(...)
```

AID/CAPK sudah bekerja: kartu Mastercard terdeteksi, AID `A0000000041010` dan CAPK index `06`
berhasil ditemukan; kegagalan baru terjadi setelah user mengonfirmasi PIN.

Temuan source reference:

- `edc-sdk/core/KeyManager.kt` menyediakan `writePIK()` terpisah dari `writeIPEK()` dan
  `decrypt()` melempar `PIK tidak ditemukan` jika static PIK belum tersedia.
- `edc-sdk/topwize/.../EmvConfiguration.kt:230-238` mendekripsi hasil PIN pad melalui jalur PIK.
- app-v3 mengisi PIK hardcoded melalui `KeyManager.writePIK(Util.pinKey())` di
  `SplashActivity.kt:590`; **jangan menyalin pola hardcoded ini**, karena user eksplisit meminta
  key transaksi berasal dari provisioning DUKPT.
- Installer project ini saat ini hanya memanggil `KeyManager.writeIPEK(ipek, ksn)` untuk purpose
  `PIN`; itu tidak mengisi slot PIK yang dicari `KeyManager.decrypt()`.

Next action: selesaikan boundary antara PIN pad Topwise (yang saat ini meminta static PIK) dan
material `PIN` DUKPT provisioning. Periksa penuh `KeyManager.decrypt`, implementasi Topwise
`SystemKey`, dan kontrak pinpad vendor sebelum mengubahnya. Solusi tidak boleh memakai
`Util.pinKey()`, test key, atau key hardcoded. Idealnya edc-sdk menghasilkan PIN block langsung di
bawah DUKPT hardware menggunakan IPEK/KSN provisioning; jika API vendor hanya mendukung static
PIK, perubahan perlu dilakukan di repo `edc-sdk`/adapter dengan desain key lifecycle eksplisit,
bukan diam-diam menganggap IPEK sebagai PIK.

### Bukti transaksi dip tanpa PIN — BERHASIL

Sesudah error PIN di atas, transaksi dip lain pada `15:09:41` berhasil sampai backend:

```text
POST /v1/cdcp/sales -> HTTP 200
entryMode: 071
keySetVersion: 3
trackKsnIndex / amountKsnIndex / emvKsnIndex: 0000A
status: AUTHORIZED
responseCode: 00
transactionId: 01a0c829-e1d7-71fa-8ff1-9feb1ded5440
```

Ini membuktikan reader chip, AID/CAPK, ICC extraction, DUKPT TRACK/AMOUNT/EMV payload,
request signing, dan endpoint CDCP sudah interoperable. Request tersebut **tidak** membawa
`pinblockEnc` atau `pinKsnIndex`, sehingga merupakan transaksi tanpa PIN/PIN bypass dan tidak
membatalkan finding `PIK tidak ditemukan`. Acceptance berikutnya wajib memakai kartu atau nominal
yang benar-benar meminta online PIN dan memastikan payload memiliki kedua field PIN tersebut.

### Catatan working tree yang wajib dipertahankan

- Working tree memang besar dan belum di-commit; semua perubahan UI/transaksi adalah bagian task
  aktif. Jangan membersihkan/reset file yang tidak dibuat sendiri.
- `ProvisionDeviceUseCase.PURPOSES` berubah dari tiga purpose menjadi
  `TRACK, AMOUNT, PIN, EMV` saat sesi berjalan. Ini dianggap **perubahan user/concurrent** dan tidak
  ditimpa. Akibatnya full `gradlew build` terakhir gagal pada test provisioning lama yang masih
  mengembalikan tiga material. Targeted SDK/feature/app build tetap hijau.
- `CardPayloadFactory` masih mengenkripsi ICC memakai purpose `TRACK` berdasarkan kontrak lama tiga
  key. Setelah kontrak backend `EMV` dikonfirmasi, sinkronkan installer, provider, payload factory,
  dan seluruh test sebagai satu perubahan; jangan hanya membuat test lama hijau secara kosmetik.
- Perbaikan loop Binder dan test regresinya juga belum di-commit.

## Status terkini — 2026-09-22 (update 3, transaksi kartu terintegrasi)

Working tree berisi implementasi yang belum di-commit untuk alur transaksi kartu produksi.

- Ditambahkan modul `feature-card-payment`. UI mengikuti layar pembayaran kartu `mobile-apps-cashlez/app-v3`, sedangkan state transaksi, pemilihan reader mPOS, retry/idempotency, dan cleanup PIN block berada sepenuhnya di feature tersebut. Implementasi sale lama di `:app` dihapus agar logic tidak rangkap.
- `AppContainer` hanya menjadi composition root: `DeviceSdkFactory` menyediakan `CardReader`, lalu `SaleRepository` mengirim transaksi memakai `deviceId` dan `keySetVersion` provisioning yang aktif.
- Payload transaksi memakai key DUKPT hasil provisioning dengan tiga purpose resmi: `TRACK`, `AMOUNT`, dan `PIN`. ICC/EMV dienkripsi dengan purpose `TRACK`; key `EMV` bayangan yang tidak pernah diprovision sudah dihapus.
- AID (`emv_parameters.format.json`), contactless AID (`emvcl_paremeters.format.json`), CAPK production (`capks.format.json`), dan tag profile disalin dari `mobile-apps-cashlez/app-v3`. Adapter built-in EDC dan mPOS memuat parameter ini secara eksplisit sebelum memulai kernel EMV.
- Findings review vendor #2–#6 sudah ditangani: dead mPOS session tidak disimpan, parameter kernel wajib dimuat, unit nominal didokumentasikan, kegagalan factory dilog, dan probe EDC diberi reentrancy/cancellation guard. `checkModuleBoundaries` juga terhubung ke task `check` dan mencakup feature module.
- Fake reader/key installer tetap hanya test fixture; tidak ada fake yang masuk ke runtime produksi.

Verifikasi terakhir berhasil:

```text
gradlew.bat :device-sdk-edcsdk:testDebugUnitTest :device-sdk-mpos:testDebugUnitTest :device-sdk-factory:testDebugUnitTest :cdcp-core:testDebugUnitTest :feature-card-payment:testDebugUnitTest :app:assembleDebug checkModuleBoundaries
BUILD SUCCESSFUL (184 tasks; module boundaries: 10 modules checked)

gradlew.bat build
BUILD SUCCESSFUL (658 tasks; debug/release, unit tests, lint, dan resource verification)
```

Yang masih perlu dilakukan sebelum produksi: uji end-to-end pada terminal fisik untuk insert/tap/swipe, online PIN, AID selection, host approval/decline, serta validasi parameter AID/CAPK dengan acquirer yang digunakan.

### Koreksi UI parity app-v3

UI awal yang dibuat pada update ini hanya meniru gaya umum app-v3 dan masih menggabungkan input nominal dengan waiting-card. Setelah review user, flow dikoreksi mengikuti sumber sebenarnya:

- State awal sekarang merupakan simple calculator 4×5 dari `feature/pos/activity_calculator.xml`: header putih/logo/status online, expression, total Rupiah, tombol `C`, `%`, operator, `000`, `=`, backspace, dan `OK`.
- Setelah `OK`, baru tampil waiting-card dari `app-v3/activity_payment_with_credit_debit.xml`: `bg_container.webp`, header “Menunggu Pembayaran”, card putih radius 28dp, `anfu.gif`, prompt, countdown, dan tombol outlined ganti metode.
- Font Instrument Sans, logo Cashup, dan background container disalin langsung dari app-v3. Calculator logic berada di `SimpleCalculator`, bukan di Fragment, serta memiliki unit test untuk evaluasi dan batas nominal sembilan digit.
- APK debug dipasang dan calculator diperiksa langsung pada terminal `HI14593000255`. Terminal terputus dari ADB ketika inisialisasi SDK reader dimulai setelah `OK`, sehingga validasi visual waiting-card dilakukan dari resource/layout dan build; pengujian transaksi fisik tetap diperlukan.
- Full `gradlew.bat build` setelah koreksi UI berhasil (658 tasks), termasuk debug/release, unit test, lint, dan resource verification.

## Status terkini — 2026-09-22 (update 2, akhir sesi — lanjut di sesi berikutnya karena limit)

Branch: `feat/provisioning-edc-mobile-adoption`. Working tree **bersih** (semua yang disebut di bawah sudah di-commit) kecuali kerjaan Anda sendiri yang mungkin belum di-commit di luar scope ini — cek `git status` saat resume.

### Sub-project 1 (Vendor SDK Abstraction) — implementasi SELESAI, full build hijau, tapi ADA PR review findings belum di-fix

Spec: `docs/superpowers/specs/2026-09-22-device-sdk-vendor-abstraction-design.md`. Plan: `docs/superpowers/plans/2026-09-22-device-sdk-vendor-abstraction.md`. Ledger lengkap tiap task (siapa dispatch, apa temuan, ruling apa): `.superpowers/sdd/2026-09-22-device-sdk-vendor-abstraction/progress.md` — **baca ini dulu kalau resume**, berisi jejak lengkap semua keputusan.

Semua 10 task plan sudah diimplementasikan via subagent-driven-development (fresh implementer + reviewer per task), semua clean/approved. Urutan commit (`30dda04`..`44ca3f8`, branch ini):
```
4f96be8  build: vendor other/newland-mpos/topwise-mpos AARs dari edc-sdk 1.0.63
eed6ba8  feat(device-sdk-api): add PairableCardReader
5c31675  build: scaffold device-sdk-mpos module
08fe55c  feat(device-sdk-mpos): EMV transact plumbing (MposEmvGateway)
128bea8  feat(device-sdk-mpos): MposCardReader dengan pairing
f86460f  fix(device-sdk-mpos): propagate CancellationException di selectDevice
1b28d8d  feat(device-sdk-mpos): MposConnector + brand registration
c22bb24  feat(device-sdk-edcsdk): EdcSdkConnector probe wrapper
de518e5  feat(device-sdk-factory): DeviceSdkFactory, single fan-out point
e404fec  build: checkModuleBoundaries Gradle task
0257e00  feat(app): wire DeviceSdkFactory ke AppContainer
c66ae4e  feat(cdcp): extend CardReader ke real EMV transact + EdcSdkCardReader/EmvGateway
         (ini kerjaan ANDA yang sudah ada di working tree sejak awal sesi — baru
         di-commit di akhir sesi ini karena final review menemukan branch tidak
         bisa di-build dari clone bersih tanpa ini; lihat "Temuan Critical" di bawah)
fc32af9  docs: plan corrections yang ditemukan saat eksekusi (JUnit5, CancellationException, noApiScope)
44ca3f8  fix(device-sdk-mpos): tambah androidx.appcompat (release resource link gagal tanpa ini)
```

**`./gradlew clean build` (SEMUA modul, semua variant, lint+test+check) — BUILD SUCCESSFUL** (572 tasks) — diverifikasi di akhir sesi ini, setelah dua bug nyata ditemukan & diperbaiki (lihat di bawah).

### Dua bug nyata yang ditemukan & diperbaiki di luar scope task manapun

1. **Blocker AAR di `edc-sdk` (repo sibling)** — dua masalah terpisah, keduanya diperbaiki di `edc-sdk` (commit `111bdde`, `5ec6d1a`) dengan otorisasi eksplisit user:
   - `:other:bundleReleaseAar` gagal karena direct local `.aar` file dependency (AGP 8.4.0 melarang ini pada module yang sendirinya di-bundle jadi AAR) → diubah ke koordinat `flatDir`.
   - `:other`'s `minifyEnabled true` tanpa keep-rules meng-obfuscate `DeviceSession`/`DeviceConnectionManager`/`BrandRegistry` (dibuktikan decompile: `com/lib/device/**` jadi `a/a.class` dst) → `minifyEnabled false`, samakan dengan konvensi `:newland-mpos`/`:topwise-mpos` yang sudah begitu.
2. **Critical finding dari final whole-branch review**: branch ini **tidak bisa di-build dari clone bersih** karena `device-sdk-api/CardReader.kt` versi baru (`transact()`) dan `EdcSdkCardReader.kt`/`EmvGateway.kt` (kerjaan Anda) **belum pernah di-commit** — semua task (4 dst) dibangun di atas working tree, bukan git history. Diperbaiki dengan commit `c66ae4e` (atas persetujuan Anda: "ok lanjutkan").
3. **`build.gradle.kts` root sempat rusak** (uncommitted, entah dari mana — bukan dari sesi ini) saat Anda lapor "build gagal": ada `repositories { mavenCentral() }` di level root yang konflik dengan `FAIL_ON_PROJECT_REPOS` di `settings.gradle.kts`, plus duplikat plugin Kotlin versi beda. **Sudah di-revert** (`git restore`) atas persetujuan Anda. Folder aneh `%LOCALAPPDATA%/` yang muncul di root repo juga sudah dihapus.
4. **`device-sdk-mpos` gagal link resource di build release** — AAR `logger-release_1.0.2.aar` (dipakai bersama `device-sdk-edcsdk`) butuh `androidx.appcompat`, belum ada di `device-sdk-mpos/build.gradle.kts`. Ditambahkan (`44ca3f8`), sama seperti yang sudah ada di `device-sdk-edcsdk`.

### BELUM SELESAI — final review menemukan findings yang belum di-fix

Final whole-branch review (dispatch Opus) verdict: **"Ready to merge? No"** sebelum Critical di atas diperbaiki; setelah Critical diperbaiki + full build hijau, **Important findings di bawah ini BELUM di-fix** (next action prioritas kalau resume):

- **#2** `MposCardReader.selectDevice()` (device-sdk-mpos/.../MposCardReader.kt) — set `gateway` SEBELUM cek `session.isAlive`; kalau `false`, gateway mati tetap tertinggal dan `transact()` akan coba jalan di session mati alih-alih fail-fast yang jelas.
- **#3** `RealMposEmvGateway.start()` (device-sdk-mpos/.../MposEmvGateway.kt) — diam-diam lanjut dengan kernel EMV belum ter-konfigurasi kalau `activity == null` (versi `device-sdk-edcsdk` aslinya `error(...)` keras di kasus ini).
- **#4** Unit `amount` di `MposEmvGateway.start()` (minor units) vs `EmvGateway.start()` (rupiah) — tidak didokumentasikan, potensi bug uang kalau ada yang copy-paste silang.
- **#5** `DeviceSdkFactory.connect()` — swallow semua vendor failure tanpa log sama sekali, susah didebug di lapangan (gap di spec §6, bukan cuma implementasi).
- **#6** `EdcSdkConnector` — tidak ada reentrancy guard (beda dari `MposConnector`'s `AtomicBoolean`) dan tidak ada `invokeOnCancellation` — berisiko double-probe kalau dipanggil ulang dari `viewModelScope` yang di-cancel lalu di-relaunch (pola pemakaian nyata yang didokumentasikan di `AppContainer`).
- **Minor #7-14** (lihat detail lengkap di final review output, tersimpan di ledger workspace kalau belum terhapus, atau re-derive dari `.superpowers/sdd/.../progress.md`): `checkModuleBoundaries` tidak di-wire ke `check` (tidak ada CI di repo ini yang otomatis panggil); api-leak check cuma cek config `api`, bukan varian `debugApi` dst; spec §3 diagram vs boundary map tidak sinkron soal `cdcp-core`→`device-sdk-factory`; `MposCardReader.context` field tidak dipakai; inkonsistensi `gateway` field-vs-local di `callbacks()`; `runCatching` di `onOnline`/`stop()` menelan `Throwable` termasuk `Error` (kontra spec §6); Task 10 menambah permission Bluetooth/Location/NFC ke manifest `:app` secara tidak kondisional (efek AAR `device-sdk-mpos`) — perlu dicatat di spec/plan, bukan ditemukan pas rilis.

**Belum invoke `finishing-a-development-branch`** — itu langkah setelah fix wave findings di atas (satu fix dispatch konsolidasi + satu scoped re-review, sesuai proses `subagent-driven-development`), belum dijalankan karena sesi keburu limit.

### Permintaan user yang masih PENDING (belum mulai dikerjakan)

User minta (`device sdk gabungkan jadi 1 saja gimana?` → lalu spesifik: `gabung device-sdk-edcsdk+device-sdk-mpos tapi biarkan device-sdk-factory+device-sdk-api terpisah`) — **belum dieksekusi**, sempat ditunda karena final review sedang jalan, lalu final review menemukan Critical/Important findings di atas yang jadi prioritas duluan. Kalau resume dan mau lanjutkan permintaan ini: perlu diskusi ulang apakah masih relevan setelah Important findings #2-#6 di atas selesai (beberapa findings, terutama #6 soal `EdcSdkConnector` vs `MposConnector` reentrancy, mungkin lebih mudah diperbaiki SEBELUM merge modul, supaya tidak dobel kerjaan). Alasan kenapa `device-sdk-factory`+`device-sdk-api` TETAP harus terpisah (sudah dijelaskan ke user): circular dependency — `device-sdk-edcsdk`/`device-sdk-mpos` butuh `device-sdk-api` untuk implement kontrak, sementara `device-sdk-factory` butuh depend ke `device-sdk-edcsdk`+`device-sdk-mpos`; kalau `device-sdk-api` digabung ke `device-sdk-factory`, jadi lingkaran yang Gradle tidak bisa resolve.

### Rekomendasi langkah berikutnya (urutan)

1. Fix Important findings #2-#6 (fix wave konsolidasi, bukan satu-satu — ikuti proses `subagent-driven-development`'s Final Review section: satu dispatch fix mencakup semua, satu scoped re-review).
2. Baru eksekusi permintaan merge `device-sdk-edcsdk`+`device-sdk-mpos` (kalau user masih mau, setelah baca ringkasan ini) — perlu update spec §3 (boundary rule "tidak boleh saling depend" jadi tidak relevan untuk pasangan ini), `checkModuleBoundaries`'s `allowedProjectDeps`, `settings.gradle.kts`, pindahkan file `device-sdk-mpos/*` ke `device-sdk-edcsdk/`, re-test.
3. Invoke `finishing-a-development-branch` untuk keputusan integrasi (branch ini masih berisi banyak kerjaan lain sebelum sub-project 1 juga — provisioning J1, TR-34 adoption — jadi "selesai" di sini bukan berarti langsung push/PR, itu keputusan terpisah).
4. Baru setelah sub-project 1 benar-benar kelar (termasuk keputusan merge modul di atas): mulai sub-project 2 (UI parity app-v3) atau 3 (transaction logic refactor) — lihat roadmap doc.

**Dokumen master 3-sub-project (tidak berubah sejak ditulis):** `docs/superpowers/specs/2026-09-22-vendor-sdk-ui-transaction-adoption-roadmap.md` — berisi riset lengkap `edc-tms-agent`/`mobile-apps-cashlez`/project ini, status sub-project 2 & 3 (belum mulai, baru riset awal).

---

## Riwayat (2026-09-14 → 2026-09-17) — foundation, provisioning J1, adopsi kontrak edc-mobile

*(Bagian di bawah ini adalah log asli sesi-sesi sebelumnya, dipertahankan sebagai arsip historis.)*

### How this project started

Session began analyzing the **old** app repo (`cash-pay-tech-mobile-function` — Cashlez POS, multi-module Android) to catalog every endpoint across all modules ahead of a full rework. That analysis is saved there at `docs/ENDPOINT_INVENTORY_FOR_REWORK.md` — ~130 HTTP endpoints across 16 service interfaces, MQTT topics, deeplink schemes, dual-auth architecture, and flagged security issues (hardcoded AES key/IV + trust-all TLS in `bridge-api`).

From a shared architecture whiteboard photo, the actual ask turned out to be narrower: build a **new**, separate project — `mobile-cashup-payment` — for just the **EDC channel** of a multi-channel backend (EDC / Softpos / CashlezLink, each with its own Front-facing API, all behind a shared **Avatar Core**). Softpos and CashlezLink are other teams' apps; out of scope here.

### What `mobile-cashup-payment` is

A **lean payment-execution engine** for physical EDC terminals (PAX/Sunmi/Feitian/Urovo/Centerm/Newland/Nexgo/Topwise/Tianyu) — **not** a POS app with a product catalog/cart. It handles CDCP (card) + QRIS payments, triggered three ways: standalone (cashier menu), app-to-app/deeplink (external caller), and ECR/POSH bridge (external controller device).

Full design spec: **`docs/EDC_PAYMENT_APP_DESIGN.md`**. Key decisions baked in there:
- **No login at all, ever.** Auth is digital-signature request-signing, locked by the team's provisioning diagram. Provisioning bootstrap has no login step either: authorization comes entirely from the `challengeCode` a QR scan yields.
- **DUKPT handling**: vendor SDK secure-module injection first, TEE vault as a mirror — implemented via `device-sdk-edcsdk`, one adapter over the `edc-sdk` AAR.
- **Fully stateless** — no local DB. Backend (Front-facing API / Avatar Core) is the single source of truth for history/reprint/settlement.
- **Payment notif**: MQTT push (unified across CDCP+QRIS) + 5s fallback to polling.
- **Signing**: same `busways.jks` keystore file as the old app, but a new alias with a fresh key pair — deliberately not cert-compatible with the `tms-agent` companion app's signature-permission channel (integration explicitly deferred).
- Explicit **performance/no-redundancy/minimal-animation** constraints (trim vs. the old app's bloat) — konsisten dengan aturan "no redundant function" yang jadi tema inisiatif 2026-09-22 juga.

### Foundation (done, merged to `main` at `bc48699`)

Plan doc: `docs/superpowers/plans/2026-09-15-foundation.md`. Tiga modul pure Kotlin/JVM (`common-core`, `device-sdk-api`, `signing-core`), 28/28 test lulus, dikerjakan via subagent-driven-development.

### Device SDK vendor adapters plan — cancelled (2026-09-16)

`docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md` (9 tasks, 67 langkah, tidak pernah dieksekusi) dibatalkan: `edc-sdk` (AAR internal) sudah menyediakan deteksi device, injeksi DUKPT, printer, card reader, EMV untuk tujuh vendor. `DeviceSdkRegistry` (matcher `Build.MODEL`-prefix) dihapus sebagai redundan. **Keputusan ini yang jadi dasar pertimbangan spec sub-project 1 tanggal 2026-09-22** — tidak dibalik, tapi diperluas untuk kasus mPOS Bluetooth yang genuinely tidak dicover `edc-sdk`.

### Provisioning J1 — done

Spec: `docs/superpowers/specs/2026-09-16-provisioning-design.md`. Diimplementasikan 15 task di branch `feat/provisioning`. Modul: `device-sdk-edcsdk` (adapter `edc-sdk` AAR), `signing-core` (Ed25519), `provisioning-core` (ceremony 10-langkah), `app` (4 layar provisioning).

### Adopsi kontrak `edc-mobile` penuh (2026-09-17 → sekarang, branch `feat/provisioning-edc-mobile-adoption`)

Spec: `docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md` — membalik otoritas dari diagram tim internal ke `edc-mobile` (project sibling yang sudah terbukti jalan lawan `corepayment` sungguhan). Perubahan utama: `deviceId` UUID backend (bukan `serialNumber`), 3 purpose DUKPT tetap (`TRACK`/`AMOUNT`/`PIN`), `/package` jadi `GET`+header, paket key hybrid RSA-OAEP+AES-GCM+Ed25519-signed, sertifikat self-signed X.509 (proof-of-possession), state model dua-fase (identity vs DUKPT). Ini **menjawab sebagian besar item "blocking backend confirmation"** di bawah — bukan lewat konfirmasi backend langsung, tapi lewat adopsi perilaku `edc-mobile` yang sudah proven:
- Item 1a (kapasitas RSA-2048/OAEP) & item (skema hybrid) — **terjawab**: hybrid RSA-OAEP+AES-GCM diadopsi.
- Item 2 (nama/jumlah purpose DUKPT) — **terjawab**: 3 tetap (`TRACK`/`AMOUNT`/`PIN`).
- Item 3 (bentuk request `/activate`) — **terjawab**: lihat spec §2 langkah terakhir.
- Item 4 (`serialNumber` vs `deviceId` UUID) — **terjawab**: `deviceId` UUID backend.
- Item 5 (path endpoint mirror `corepayment`) — **terjawab**: dikonfirmasi cocok.
- Item 6 (`GET` vs `POST` untuk `/package`) — **terjawab**: `GET`+header.
- Item 1b (MGF1 digest SHA-1/SHA-256) & item 8 (daftar kode error) — **belum ada bukti eksplisit terselesaikan** di spec ini, perlu dicek kalau relevan lagi.
- Item 7 (canonical string/signature format) — sudah diimplementasikan di `signing-core` (Ed25519RequestSigner), tapi konfirmasi eksplisit dari backend sungguhan belum tercatat.

Commit terbaru terkait di `main`/branch ini (per `git log`): `b050f32 fix: complete TR-34 provisioning and log sale requests`, `9a6615d feat: align provisioning and sale API flow`, `6c50c2f feat(provisioning-core): make RsaKeyStore dual-purpose, add certificate generation`, `3ec0300 refactor(provisioning-core): split provisioning state into identity and DUKPT`.

### Older open items — payment domain (belum tentu masih relevan, cek ulang kalau dipakai)

- MQTT topic/payload design (belum dibangun — lihat `notification-core` di "What's next" lama).
- Definitive list of Front-facing API (CDCP+QRIS) endpoints.
- QRIS Static status query per-invoice/session (bukan "last transaction for merchant").
- "Pending transaction" reconciliation endpoint contract (app ini stateless).
- TMS Agent integration trust reconfiguration (deferred, belum dibuka lagi).
