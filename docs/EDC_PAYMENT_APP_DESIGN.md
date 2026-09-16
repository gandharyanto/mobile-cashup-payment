# mobile-cashup-payment — EDC Payment App Design Spec

**Status**: Draft — menunggu review
**Tanggal**: 2026-09-14
**Channel**: EDC (dari arsitektur multi-channel: **EDC** / Softpos / CashlezLink → masing-masing punya Front-facing API sendiri → **Avatar Core**)
**Tim**: Taufik + Gandha + Richard (EDC channel)

---

## 1. Ringkasan & Tujuan

`mobile-cashup-payment` adalah **payment-execution engine** yang berjalan di physical EDC terminal (PAX/Sunmi/Feitian/Urovo/Centerm/Newland/Nexgo/Topwise/Tianyu) — **bukan** aplikasi POS dengan katalog produk/cart. Tugasnya murni: terima instruksi bayar (amount + tipe pembayaran), proses via hardware EDC (kartu) atau QRIS, laporkan hasilnya.

Tiga jalur pemicu transaksi:
1. **Standalone** — cashier langsung di menu Home device, input amount manual.
2. **App-to-app / deep link** — aplikasi kasir eksternal memanggil device ini (pola `cashlez://` yang sudah ada, dipertahankan).
3. **ECR/POSH bridge** — controller device eksternal memanggil & menerima notifikasi status via bridge.

Tidak ada login username/password untuk **operasional harian** — transaksi (sale/void/settlement/dst) berjalan tanpa cashier login sama sekali, device diautentikasi ke Front-facing API via **digital signature request-signing**. Proses **bootstrap** (provisioning, re-provisioning, deactivation) tetap butuh login: teknisi login pakai kredensial admin Cashup **langsung di device**, baru lanjut scan QR. App ini **fully stateless** — tidak ada local database; semua histori/reprint/settlement query live ke Front-facing API (CDCP+QRIS).

> **Konfirmasi**: ketiga jalur pemicu di atas (Standalone/manual-entry, App-to-app, ECR) sudah dikonfirmasi masuk scope v1.

---

## 2. Keputusan Arsitektur Kunci (Rangkuman)

| Area | Keputusan |
|---|---|
| Auth ke Front-facing API | Digital signature (bukan bearer token) — key didapat sekali saat provisioning |
| Login user di device | **Tidak ada.** Admin login di Cashup backoffice (sistem terpisah), device di-provisioning via QR scan |
| Provisioning bootstrap | Tidak ada login. Otorisasi provisioning sepenuhnya datang dari `challengeCode` hasil scan QR yang diterbitkan Cashup backoffice. |
| Signing key storage | Signing key Ed25519 **TIDAK** hardware-backed — `KEY_ALGORITHM_ED25519` tidak ada di Android sampai API 37. Yang berada di TEE: key RSA pembuka paket (bila digest MGF1 backend memungkinkan, lihat spec provisioning §4.5) dan vault DUKPT. |
| DUKPT key handling | **Tidak berubah dari spec saat ini** — key component di-inject ke secure crypto module vendor EDC via SDK vendor masing-masing, tidak pernah dipegang app sebagai raw key material |
| Payment notif | MQTT push, topic terunifikasi untuk semua tipe pembayaran (CDCP+QRIS), fallback ke status-check polling kalau tidak ada push dalam 5 detik |
| Storage transaksi | **Fully stateless** — tidak ada DB lokal. Reprint/histori/settlement selalu live-query ke Front-facing API. Recovery pasca-crash: tanya backend "ada transaksi pending untuk device ini?" |
| Scope CDCP v1 | Sale, void, reversal, installment, settlement, reprint (loyalty-point redemption & card-routing-check di luar v1) |
| Scope QRIS | Dynamic QR + Static QR |
| Dukungan vendor SDK | Semua 9 vendor sejak v1 (full parity dengan aarlib yang ada sekarang) |
| Arsitektur modul | Multi-module (core/feature terpisah per Gradle module) |
| Provisioning lifecycle | Initial provisioning + re-provisioning (refresh key) + deactivation/factory reset |
| Code signing | `busways.jks` (keystore file sama dengan `mobile-apps-cashlez`), tapi **alias baru dengan key pair baru** — certificate/fingerprint berbeda dari `mobile-apps-cashlez` & `tms-agent` (lihat §9) |

---

## 3. Struktur Modul

| Module | Tanggung jawab |
|---|---|
| `app` | Shell: DI wiring, navigasi, entry activities (deeplink receiver, provisioning, payment, settlement/reprint) |
| `provisioning-core` | QR scan → call provisioning API (otorisasi via `challengeCode`, tanpa login) → terima DUKPT/signing key+config → simpan signing key ke Keystore & inject DUKPT ke vendor SDK → re-provisioning & deactivation. Dipakai oleh `app`. |
| `signing-core` | Interceptor cross-cutting yang menandatangani setiap outgoing request pakai signing key hasil provisioning |
| `cdcp-core` | Domain kartu: sale, void, reversal, installment, settlement, reprint |
| `qris-core` | Domain QRIS: generate dynamic + static, status, cancel |
| `notification-core` | Client MQTT, topic payment-status terunifikasi (didesain baru), orchestrator fallback→poll 5 detik |
| `device-sdk-api` + `device-sdk-edcsdk` | Abstraksi hardware. Kontrak umum di `device-sdk-api`; satu module adapter di atas AAR `edc-sdk`, yang sudah menyediakan deteksi device, serial number, injeksi key DUKPT, printer, card reader, dan EMV untuk tujuh vendor. Sembilan module adapter per vendor dibatalkan — lihat `docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md`. |
| `bridge-api` | Client ECR/POSH bridge — dibangun ulang tanpa 2 lubang keamanan dari audit repo lama (AES key/IV hardcoded, trust-all TLS) |
| `common-core` | Network client bersama, model error, logging (Graylog GELF — dipertahankan), helper template/print struk |

---

## 4. User Journeys

### J1 — Provisioning (Setup Pertama Kali)
**Actor**: Admin (Cashup backoffice, sistem terpisah) + Teknisi/Merchant yang pegang device.

**Tidak ada login teknisi.** Otorisasi datang sepenuhnya dari `challengeCode` hasil scan QR. Alur sepuluh langkah (App Provisioning · EDC · Backend · Payment HSM · General Purpose HSM):

1. App generate QR berisi `challengeCode` (ditampilkan lewat Cashup backoffice).
2. Device scan QR → decode `challengeCode`.
3. Device generate keypair RSA (unwrap paket) dan EdDSA (signing request).
4. Device → Backend: `QR Redeem` — `{ challengeCode, serialNumber, rsaPublicKey, eddsaPublicKey }`.
5. Backend validasi QR & nonaktifkan challenge-code.
6. Backend → Device: `{ orderId, activationToken }`.
7. Device → Backend: `Get DUKPT` — `{ orderId, activationToken }`; backend generate `keyId` (KSN), ambil IPEK dari Payment HSM dan Ed25519 public key dari General Purpose HSM.
8. Backend → Device: `Send IPEK` (dibungkus RSA) — `{ orderId, wrappedPackageKey, appEddsaPublicKey }`.
9. Device unwrap paket di dalam TEE, inject DUKPT key ke secure module vendor (via SDK vendor aktif) dan simpan signing key Ed25519 (bukan hardware-backed — §2), lalu kirim KCV ke backend; backend balas OK/NOK.
10. Sukses → screen **Provisioning – Result (Sukses)** tampil info terminal → lanjut ke **Home**. Gagal di langkah mana pun → **atomic**: semua key dihapus, device tetap berstatus belum terprovisioning, tidak ada state "setengah jalan".

Kontrak HTTP lengkap, kripto, dan batasannya ada di `docs/superpowers/specs/2026-09-16-provisioning-design.md`.

### J2 — Re-Provisioning (Refresh Key)
1. Dari **Home**, masuk menu **Device Settings** → **Re-Provisioning**.
2. Teknisi login pakai kredensial admin Cashup (sama seperti J1 langkah 1–2) → dapat temp JWT.
3. Lanjut alur sama seperti J1 langkah 3–7 (scan QR → call endpoint refresh, bukan initial) — key lama digantikan, bukan device baru.
4. Hasil sukses/gagal ditampilkan sama seperti J1.

### J3 — Deactivation / Factory Reset
1. Dari **Home** → **Device Settings** → **Deactivate Device**.
2. Teknisi login pakai kredensial admin Cashup (sama seperti J1 langkah 1–2) → konfirmasi deaktivasi.
3. App panggil endpoint deactivation (pakai temp JWT dari login) → backend invalidate device tsb.
4. App hapus signing key dari Keystore + instruksikan vendor SDK menghapus DUKPT key dari secure module.
5. Device kembali ke state belum terprovisioning → redirect ke **Provisioning – Login Teknisi**.

### J4 — Standalone Sale (Kartu / CDCP)
1. Cashier di **Home** pilih **Sale** → **Sale Entry** (satu screen: input nominal + pilih **Card**).
2. **Card Payment Processing**: prompt tap/insert/swipe (UI ini sebagian besar disediakan vendor SDK), PIN entry kalau perlu.
3. App tandatangani request sale, kirim ke Front-facing API (CDCP+QRIS).
4. Tunggu hasil: **Card Payment Result** (approved/declined), tampilkan detail (amount, masked PAN, approval code).
5. Opsi lanjut: **Print Receipt**, **Done** (kembali ke Home), atau **Void** transaksi ini.

### J5 — Standalone Sale (QRIS Dynamic)
1. Cashier di **Home** pilih **Sale** → **Sale Entry** (satu screen: input nominal + pilih **QRIS Dynamic**).
2. App generate QR dinamis (amount ter-embed) via Front-facing API → tampil di **QRIS Generate (Dynamic)**.
3. Tunggu status: MQTT push utama, fallback poll tiap interval kalau >5 detik tanpa push.
4. Sukses → **QRIS Payment Result**; timeout/cancel tersedia sebagai opsi di screen ini.

### J6 — Standalone Sale (QRIS Static)

> **Verifikasi dari implementasi existing** (`feature/qris-static`): "static" berarti **tanpa amount ter-embed**, BUKAN "generate sekali lalu dipakai berulang". Setiap sesi tetap generate ulang & ter-asosiasi ke invoice/transaksi baru di backend; kontennya saja yang tidak membawa nominal.

1. Cashier di **Home** pilih **Sale** → **Sale Entry**, pilih **QRIS Static** (tanpa isi nominal — customer input sendiri di aplikasi e-wallet mereka).
2. App panggil generate QR (fresh setiap sesi, bukan cache) → backend balas payload QR **terenkripsi** (skema existing: DUKPT-encrypted) + reference invoice/transaction id untuk sesi ini.
3. App decrypt payload QR pakai DUKPT key (key yang sama dari secure module vendor, §2) → render QR di **QRIS Generate (Static)**.
4. App menunggu status lewat dua jalur paralel (pola existing yang dipertahankan):
   - **MQTT push** — diproses begitu diterima di topic device ini, **tanpa gating "ada sesi aktif atau tidak"**, dedupe hanya by invoice/transaction id yang sudah diproses.
   - **Poll fallback** (>5 detik tanpa push) — query transaksi terbaru, lalu **device sendiri yang mencocokkan** ke invoice/id/timestamp sesi yang sedang ditampilkan.
5. Sukses → **QRIS Payment Result** menampilkan amount yang benar-benar dibayar (bukan yang di-set cashier, karena static QR tidak punya amount pre-set).

### J7 — App-to-App Invocation (Card / QRIS)
1. Aplikasi kasir eksternal kirim intent/deeplink (amount, tipe, callback URL) ke device ini.
2. App validasi device sudah terprovisioning; kalau belum → tolak dengan error result langsung ke callback.
3. App langsung masuk ke **Card Payment Processing** atau **QRIS Generate** (skip Home & Sale Entry — amount & tipe sudah dari caller).
4. Setelah selesai, **Result screen** menjalankan countdown auto-return, lalu kirim callback ke aplikasi pemanggil (status, tipe, data).
5. **Idempotency**: kalau `merchant_trx_id` + invoice number sama dengan request sebelumnya, cek status transaksi existing dulu — jangan generate baru (fix dari isu app-to-app lama, dipertahankan).

### J8 — ECR Bridge-Triggered Payment
1. Controller device eksternal (ECR/POSH) trigger pembayaran via bridge.
2. App proses sama seperti J7 (skip Home & Amount Entry).
3. Sepanjang proses & setelah selesai, app push status (`payment-status-cdcp`/`-qris`/error) ke controller via `bridge-api`, tunggu `acknowledge`.

### J9 — Void
1. Dari **Home** → **Transaction History** (live-query dari Front-facing API) → pilih transaksi → **Transaction Detail**.
2. Tap **Void** → konfirmasi → app tandatangani & kirim request void.
3. Hasil ditampilkan di **Card Payment Result** (varian void) atau dialog hasil.

### J10 — Installment Sale
1. Sama seperti J4, tapi setelah kartu terbaca & terdeteksi eligible installment, app tampilkan **Installment Selection** (pilih tenor/bank) sebelum lanjut ke processing.
2. Lanjut seperti alur sale normal.

### J11 — Settlement
1. Dari **Home** → **Settlement**.
2. App tandatangani & kirim request settlement (batch) ke Front-facing API.
3. **Settlement Result**: total transaksi, total amount, nomor batch, opsi **Print Receipt**.

### J12 — Reprint / Lookup Histori
1. Dari **Home** → **Transaction History** → filter tanggal (live-query, tidak ada cache lokal).
2. Tap transaksi → **Transaction Detail** → **Reprint**.

---

## 5. Inventaris Screen

| Screen | Trigger / Entry | Yang Dilakukan | Exit / Lanjut ke |
|---|---|---|---|
| **Splash / Bootstrap** | App start | Cek signing key di Keystore (proxy status provisioning), init koneksi MQTT, init vendor SDK yang terdeteksi | Belum terprovisioning → Provisioning – Login Teknisi; sudah → Home |
| **Provisioning – Login Teknisi** | Belum terprovisioning (boot pertama), atau masuk manual dari Device Settings (re-provisioning/deactivation) | Teknisi login pakai kredensial admin Cashup → dapat temp JWT scoped provisioning | Provisioning – Scan QR (alur provisioning/re-provisioning), atau langsung ke Deactivate Device (alur deaktivasi) |
| **Provisioning – Scan QR** | Setelah login teknisi sukses | Buka scanner/kamera, decode QR provisioning (kode provisioning) | Provisioning – Processing |
| **Provisioning – Processing** | Setelah QR ter-scan | Call provisioning API, inject DUKPT ke vendor SDK, simpan signing key ke Keystore (loading state) | Provisioning – Result |
| **Provisioning – Result** | Setelah proses selesai | Tampilkan sukses (info terminal: merchant, MID, TID) atau gagal (alasan + retry) | Sukses → Home; Gagal → Scan QR lagi |
| **Home / Standby** | Setelah provisioning sukses, atau app resume dalam state idle | Menu utama: Sale, Settlement, Transaction History, Device Settings. Juga jadi "listening state" untuk app-to-app/ECR trigger | Ke salah satu flow di bawah, atau menerima trigger eksternal |
| **Sale Entry** | Home → Sale (standalone only; dilewati kalau app-to-app/ECR — amount & method sudah dari caller) | Satu screen gabungan: numeric keypad input nominal + pilih metode (Card / QRIS Dynamic / QRIS Static) — digabung dari 2 screen terpisah supaya 1 hop navigasi lebih sedikit (§11) | Card Payment Processing atau QRIS Generate |
| **Card Payment Processing** | Method = Card (standalone atau app-to-app/ECR) | Prompt tap/insert/swipe (UI vendor SDK), PIN entry, sign+kirim request ke Front-facing API, tampilkan status processing | Card Payment Result |
| **Card Payment Result** | Setelah response Front-facing API | Tampilkan approved/declined, detail (amount, masked PAN, approval code); opsi Print/Done/Void. Kalau app-to-app: countdown auto-return + kirim callback | Home, atau kembali ke caller app |
| **Installment Selection** | Kartu terdeteksi eligible installment (J10) | Pilih tenor/bank installment | Card Payment Processing (lanjut) |
| **QRIS Generate (Dynamic)** | Method = QRIS Dynamic | Generate & tampilkan QR dengan amount ter-embed, dengarkan MQTT push, fallback poll >5 detik | QRIS Payment Result |
| **QRIS Generate (Static)** | Method = QRIS Static | Tampilkan QR statis merchant, dengarkan notifikasi pembayaran masuk | QRIS Payment Result |
| **QRIS Payment Result** | Setelah status final (push/poll) | Tampilkan sukses/gagal/timeout, amount yang benar-benar dibayar; opsi Print/Done. Kalau app-to-app: countdown + callback | Home, atau kembali ke caller app |
| **Transaction History** | Home → Transaction History | Live-query daftar transaksi dari Front-facing API dengan filter tanggal | Transaction Detail |
| **Transaction Detail** | Tap item di Transaction History | Tampilkan detail lengkap 1 transaksi (live-fetch) | Reprint, atau Void |
| **Settlement** | Home → Settlement | Trigger batch settlement, tampilkan progress | Settlement Result |
| **Settlement Result** | Setelah settlement selesai | Total transaksi, total amount, nomor batch, opsi Print Receipt | Home |
| **Receipt Preview / Print** | Dipanggil dari Card/QRIS Result, Transaction Detail (reprint), atau Settlement Result | Render & kirim struk ke printer device | Kembali ke screen pemanggil |
| **Device Settings** | Home → Device Settings | Info device (serial number, TID, MID, versi app, status koneksi network/MQTT, versi SDK vendor), akses ke Re-Provisioning & Deactivate | Re-Provisioning → Provisioning – Login Teknisi; Deactivate → Provisioning – Login Teknisi; atau kembali ke Home |
| **Deactivate Device** | Provisioning – Login Teknisi (setelah login sukses, alur deaktivasi) | Konfirmasi deaktivasi, hapus key, panggil endpoint deactivation pakai temp JWT | Kembali ke Provisioning – Login Teknisi |

**Bukan screen terpisah, tapi komponen lintas-flow yang perlu didesain eksplisit:**
- **App-to-App / Deeplink Router** — komponen (bukan screen) yang menerima intent eksternal, validasi provisioning state, lalu route langsung ke Card Payment Processing / QRIS Generate (skip Home & Amount Entry), dan mengirim callback saat selesai.
- **ECR Bridge Status Pusher** — background component yang push status ke `bridge-api` di setiap perubahan state transaksi signifikan (created, approved, declined, error), lalu tunggu `acknowledge`.
- **Reconciliation-on-launch** — dijalankan di Splash setiap app start: tanya Front-facing API "ada transaksi pending/belum resolved untuk device ini?" (pengganti local offline-queue, karena fully stateless).

---

## 6. Data Flow (Ringkas)

**Provisioning**: `QR scan` → decode → `POST provisioning (temp JWT)` → response {DUKPT components, signing key, terminal config} → paralel: [inject DUKPT ke vendor SDK] + [simpan signing key ke Keystore] → **kedua langkah harus sukses bersama (atomic), gagal salah satu = rollback total**.

**Payment (CDCP/QRIS)**: `Amount+Method (dari cashier ATAU dari app-to-app/ECR)` → `signing-core` tanda-tangani request → `Front-facing API (CDCP+QRIS)` → response `pending` → `notification-core` dengarkan MQTT topic unified → (dalam 5 detik ada push? pakai push : mulai poll `status_check`) → status final → update UI + (kalau app-to-app/ECR) kirim callback/bridge push.

**Reprint/Histori/Settlement**: selalu `GET` live ke Front-facing API — tidak ada baca dari storage lokal sama sekali.

---

## 7. Error Handling & Offline Behavior

- **Network loss saat transaksi berjalan**: tidak ada local queue. Saat app kembali online (atau saat next launch), jalankan **Reconciliation-on-launch** — tanya backend status transaksi terakhir yang device ini mulai. Kontrak endpoint ini perlu didefinisikan bareng backend (§9).
- **Signature/auth gagal**: bedakan dua kasus — (a) device di-deactivate/revoke dari backend → paksa app ke state belum-terprovisioning, tampilkan pesan jelas; (b) kegagalan transient (clock skew, network) → retry dengan backoff, jangan langsung anggap device ter-revoke.
- **Error hardware** (kartu gagal dibaca, printer kehabisan kertas, scanner gagal baca QR provisioning): tangani sebagai dialog/toast di screen terkait, bukan full-screen error — user harus bisa retry tanpa kehilangan context transaksi yang sedang berjalan.
- **QRIS static — notifikasi tanpa sesi aktif**: pola existing yang dipertahankan — MQTT push untuk QRIS Static **tidak digating oleh "ada sesi aktif atau tidak"**, hanya dedupe by invoice/transaction id. Konsekuensinya notifikasi bisa saja datang saat tidak ada QR di layar (device idle) — app tetap harus proses & log (surface ke history sebagai "unattended payment"), jangan diam-diam dibuang. Untuk jalur **poll** (dipakai saat QR sedang ditampilkan), matching-nya rawan race condition karena bergantung ke "transaksi terakhir milik merchant" bukan status per-session — lihat §9 item 5 untuk perbaikan yang diminta ke Front-facing API baru.

---

## 8. Testing Strategy

- **Unit test per domain module** — `cdcp-core`, `qris-core`, `provisioning-core`, `signing-core` diuji terisolasi dari hardware & network nyata.
- **Fake `device-sdk-api`** — implementasi palsu dari interface card reader/printer/scanner untuk testing tanpa hardware fisik, dipakai di semua module domain di atas.
- **Contract test** ke Front-facing API — begitu kontrak (List API, JSON body, Auth signing scheme) final dari backend, buat contract test supaya breaking change dari backend terdeteksi cepat.
- **Vendor SDK adapter test** — per adapter module (`device-sdk-<vendor>`), pakai simulator/test device dari masing-masing vendor kalau tersedia; kalau tidak, minimal smoke test manual per rilis terhadap device fisik.
- **E2E**: app-to-app invocation flow & ECR bridge flow diuji end-to-end karena melibatkan komponen eksternal (caller app / controller device) yang tidak bisa di-mock sepenuhnya di unit test.

---

## 9. Open Items (Perlu Diselesaikan Bareng Tim Backend / Front-facing API)

Sesuai catatan di diagram arsitektur awal ("List API", "Detail JSON Body", "Auth", "Payment notif"):

1. **Signature scheme detail** — Ed25519, sudah dikunci diagram provisioning tim. Yang tersisa: konfirmasi encoding signature (raw 64 byte, Base64 URL-safe tanpa padding).
2. **Provisioning API contract** — Terjawab; lihat spec provisioning §3. Sisa yang belum pasti terdaftar di §8 spec itu.
3. **MQTT topic & payload design** — skema topic unified yang baru (bukan warisan `payment/{userDevice}` lama), format payload terenkripsi/tidak.
4. **List API definitif** — daftar lengkap endpoint di bawah Front-facing API (CDCP+QRIS) yang tersedia untuk EDC channel.
5. **QRIS Static — status query harus session-scoped, bukan merchant-scoped**: implementasi existing mencocokkan status lewat "ambil transaksi terakhir milik merchant" lalu match client-side by invoice/id/timestamp — rawan race condition kalau >1 device merchant yang sama generate QRIS Static berdekatan waktu. Untuk Front-facing API baru, minta endpoint status **per invoice/session id** (bukan "last transaction milik merchant"), supaya device tidak perlu heuristic matching sendiri.
6. **Endpoint reconciliation "transaksi pending"** — kontrak untuk Reconciliation-on-launch (§6, §7) karena app fully stateless.
7. **TMS Agent integration** (di luar scope untuk saat ini, dicatat supaya tidak jadi silent trap nanti) — ada agent terpisah (`edc-tms-agent`, repo `tms-agent`) yang berjalan bersamaan di terminal, menangani reboot/install-APK/kiosk-lockdown/inventory device secara remote. Ada kontrak intake (`tms-appevent-api`, broadcast login/logout) yang dikonsumsi `mobile-apps-cashlez` saat ini, dilindungi `signature`-level permission yang di-pin ke fingerprint certificate `mobile-apps-cashlez`/`tms-agent` (`busways.jks` alias lama). Karena `mobile-cashup-payment` memakai **alias baru dengan key pair baru** (§2), certificate-nya **tidak otomatis ter-trust** oleh channel itu — kalau integrasi TMS ini dibutuhkan nanti, trust di sisi `tms-agent` (dan/atau konfigurasi `PAYMENT_APP_PACKAGE`) harus direkonfigurasi dulu sebelum channel diaktifkan.

## 11. Prinsip Performa & Kualitas Kode

Diminta eksplisit: minimalkan redundant logic dari codebase existing, buang proses yang tidak perlu, optimalkan untuk device low-end, dan hindari animasi yang tidak perlu. Berlaku sebagai constraint mengikat untuk implementation plan & kode, bukan sekadar saran:

- **Tidak ada logic terduplikasi lintas modul.** Scope project ini (CDCP+QRIS saja) sudah menghindari pola duplikasi 4-5 service hampir identik yang ada di `va`/`qris-static`/`bnpl`/`cnp` existing (`payment/status_check`, `trx_history/get_all_success_payment_list`, `refund`, `payment/cancel`, dll. disalin verbatim di beberapa modul). Kalau ada logic yang sama dibutuhkan CDCP & QRIS (signing, status-poll orchestrator, receipt rendering), taruh sekali di module bersama (`signing-core`, `notification-core`, `common-core`) — jangan disalin per-domain.
- **Body request/response typed, bukan `Any`.** Pola `@Body body: Any` yang mendominasi service existing (qris-core, cdcp-core, va, bnpl, dll.) tidak dibawa — semua request/response pakai data class eksplisit. Menghindari parsing manual berulang dan bug silent-mismatch yang sudah ditemukan di audit (`cdcp-core` banyak response `String` mentah).
- **Buang proses yang tidak perlu**, bukan cuma dipertahankan "karena ada di existing":
  - Amount Entry + Payment Method Selection digabung jadi satu screen **Sale Entry** (§4, §5) — 1 hop navigasi lebih sedikit tiap standalone sale.
  - Tidak ada local DB / offline-queue sync logic (§2) — menghilangkan seluruh lapisan PENDING/SYNCED reconciliation yang ada di `pos-core` existing.
  - Tidak ada dual-login flow (middleware login + POS login terpisah) — diganti no-login harian + satu login teknisi saat bootstrap (§2, §4 J1).
- **Optimasi untuk device low-end** (banyak EDC terminal jalan di SoC kelas bawah):
  - Hindari kerja berat di main thread — signing, decrypt DUKPT, dan parsing response semua di background dispatcher, UI thread hanya update state.
  - `notification-core` idle saat tidak ada transaksi berjalan — tidak polling terus-menerus; poll fallback hanya aktif dalam window sempit (>5 detik tanpa push, sampai status final atau timeout), bukan loop tanpa henti.
  - Vendor SDK (`device-sdk-<vendor>`) di-init lazy sesuai vendor yang terdeteksi di device tsb saja — bukan inisialisasi semua 9 SDK vendor di startup meskipun cuma 1 yang relevan untuk hardware itu.
  - View hierarchy flat per screen — hindari nested layout berlapis yang mahal di-measure/layout pada device dengan GPU/CPU lemah.
- **Animasi seminimal mungkin.** Transisi antar screen default instan/fade sederhana, bukan custom transition/Lottie. Elemen yang secara fungsional butuh gerakan (countdown auto-return di J7/J8, indikator loading saat signing/network call) tetap ada tapi versi paling sederhana (angka countdown polos, progress indicator standar Android) — bukan animasi dekoratif. Prioritas: responsif & instan di device lambat, bukan polish visual.

## 12. Di Luar Scope (Eksplisit)

- Katalog produk, cart, kategori, evaluasi promo — ini domain channel/aplikasi lain (bukan tanggung jawab EDC channel).
- VA, BNPL, Cash, PPOB, CNP, CashlezLink — channel terpisah (Softpos & CashlezLink punya app/API/tim sendiri per diagram arsitektur).
- Local persistent database untuk data transaksi (lihat keputusan storage di §2).
- Loyalty-point redemption & card-routing-check untuk CDCP (ditunda dari v1, lihat §2).
