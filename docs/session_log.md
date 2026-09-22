# Session Log

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
