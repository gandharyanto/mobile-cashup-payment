# Session Log

## Status terkini — 2026-09-22

Branch: `feat/provisioning-edc-mobile-adoption` (bukan `feat/provisioning` lagi — provisioning J1 sudah selesai, di-merge, dan project pindah ke adopsi kontrak `edc-mobile` penuh, lihat "Riwayat" di bawah).

**Yang baru terjadi sesi ini:** dimulai dari permintaan user untuk merencanakan tiga hal sekaligus — (1) implementasi SDK vendor dengan pola seperti `edc-tms-agent`, (2) UI identik dengan `app-v3` di `mobile-apps-cashlez`, (3) logic transaksi yang merupakan perbaikan dari `mobile-apps-cashlez` tanpa function redundan. Ini didekomposisi jadi 3 sub-project berurutan. **Dokumen master untuk inisiatif ini:** `docs/superpowers/specs/2026-09-22-vendor-sdk-ui-transaction-adoption-roadmap.md` — baca itu dulu kalau resume, berisi riset lengkap 3 project sibling (`edc-tms-agent`, `mobile-apps-cashlez`, project ini sendiri) supaya tidak perlu explore ulang dari nol.

Ringkas status 3 sub-project (detail penuh + alasan urutan ada di roadmap doc):
1. **Vendor SDK Abstraction** (EDC built-in + mPOS Bluetooth Newland/Topwise) — **spec selesai & di-commit** (`756e92e`, `docs/superpowers/specs/2026-09-22-device-sdk-vendor-abstraction-design.md`). Implementation plan belum dibuat — **ini next action prioritas**.
2. **UI Parity app-v3** — belum mulai, baru riset awal (di roadmap doc §6.2).
3. **Transaction Logic Refactor** — belum mulai, baru riset awal + daftar redundansi (`EmvViewModel` x3, `ReceiptTemplate` x3, dll — di roadmap doc §6.2 & §5).

**PENTING — perlu dicek/dikonfirmasi sebelum lanjut implementasi sub-project 1:** saat sesi ini berjalan, ditemukan working tree punya perubahan **uncommitted** di luar scope sesi ini (bukan dibuat oleh brainstorming ini — kemungkinan besar pekerjaan paralel yang sedang berjalan):
- `device-sdk-api/src/main/kotlin/com/cashup/devicesdk/CardReader.kt` — diperluas dari `waitForCard(timeoutMillis)` sederhana jadi `transact(request, listener)` penuh dengan `CardTransactionEvent`/`CardTransactionListener` (real EMV flow, bukan cuma card detect).
- `cdcp-core/.../SaleRepository.kt` & `CardPayloadFactory.kt` — tambah overload yang menerima `CardTransactionData` langsung + dukungan key `EMV` (selain `TRACK`/`AMOUNT`/`PIN`).
- File baru (belum di-`git add`): `device-sdk-edcsdk/.../EdcSdkCardReader.kt`, `EmvGateway.kt`.
- Test terkait (`FakeDeviceSdkTest.kt`, `FakeCardReader.kt`) juga sudah disesuaikan.

Spec sub-project 1 (vendor SDK abstraction) **ditulis dengan asumsi `CardReader.kt` versi baru ini** (karena itu yang terbaca saat riset). Kalau perubahan ini nanti di-commit terpisah, semua konsisten. Kalau di-revert/diubah arahnya, bagian §4/§5 spec sub-project 1 (kontrak `transact()`, `PairableCardReader`) perlu ditinjau ulang. **Konfirmasi ke user dulu sebelum menyentuh file-file ini.**

**Rekomendasi langkah berikutnya:** (a) konfirmasi status perubahan uncommitted di atas ke user; (b) invoke skill `writing-plans` dari spec sub-project 1 yang sudah disetujui untuk membuat implementation plan; (c) baru setelah itu mulai brainstorming sub-project 2 atau 3.

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
