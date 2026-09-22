# Roadmap: Vendor SDK ala TMS + UI Parity app-v3 + Refactor Transaction Logic

**Tanggal dibuat:** 2026-09-22
**Status:** aktif, dokumen hidup — update tabel status di §3 tiap kali sebuah sub-project pindah tahap.
**Project ini:** `mobile-cashup-payment` (branch `feat/provisioning-edc-mobile-adoption` saat dokumen ini ditulis).
**Tujuan dokumen:** supaya sesi Claude Code berikutnya — yang **tidak** punya histori percakapan ini — bisa langsung melanjutkan tanpa perlu menjalankan ulang riset eksplorasi dari nol. Semua temuan riset mentah disalin lengkap di §6.

---

## 1. Permintaan awal (dari user, diterjemahkan)

Project `mobile-cashup-payment` ingin:
1. Implementasi SDK vendor dengan **pola arsitektur seperti project `edc-tms-agent`** (TMS agent) — bukan berarti menyalin kode TMS, tapi pola: kontrak vendor-agnostic, satu modul adapter per keluarga vendor, factory yang probing runtime, boundary enforcement.
2. **UI aplikasi sama persis dengan `app-v3`** di project `mobile-apps-cashlez` (project sibling, path: `D:/gandha_cashup/projects/mobile-apps-cashlez`).
3. **Logic transaksi harus merupakan perbaikan (refactor)** dari logic yang ada di `mobile-apps-cashlez` — dengan aturan keras: **tidak boleh ada function yang redundan** (redundansi yang sudah ditemukan di `mobile-apps-cashlez` sendiri harus diperbaiki, bukan dipindah apa adanya).

## 2. Kenapa didekomposisi jadi 3 sub-project

Tiga permintaan di atas cukup independen untuk dikerjakan sebagai spec→plan→implementasi terpisah, tapi punya urutan dependency alami:
- **Vendor SDK abstraction** membentuk kontrak `CardReader`/`transact()`/`CardTransactionEvent` yang akan dipakai baik oleh UI (sub-project 2) maupun engine transaksi (sub-project 3). Mengerjakan ini duluan mencegah UI/transaction-logic dibangun di atas kontrak device yang belum stabil.
- **UI parity app-v3** butuh tempat memplug transaction logic — kalau dikerjakan sebelum sub-project 3 selesai, UI akan sementara memanggil stub/mock.
- **Transaction logic refactor** butuh baik kontrak device (sub-project 1) maupun kerangka UI (sub-project 2) sebagai tempat hidup akhirnya.

User memilih urutan: **(1) Vendor SDK Abstraction dulu**, lalu (2) dan (3) menyusul, masing-masing lewat siklus brainstorming→spec→plan sendiri.

## 3. Status tiap sub-project

| # | Sub-project | Status | Dokumen |
|---|---|---|---|
| 1 | Vendor SDK Abstraction (EDC built-in + mPOS Bluetooth) | **Spec selesai & disetujui user, di-commit** (`756e92e`). Implementation plan **belum dibuat**. | `docs/superpowers/specs/2026-09-22-device-sdk-vendor-abstraction-design.md` |
| 2 | UI Parity dengan `app-v3` | **Belum mulai.** Riset awal sudah ada (§6.2), belum brainstorming/spec. | *(belum ada file spec)* |
| 3 | Transaction Logic Refactor (dari `mobile-apps-cashlez`, no redundant functions) | **Belum mulai.** Riset awal + daftar redundansi sudah ada (§6.2), belum brainstorming/spec. | *(belum ada file spec)* |

**Kalau melanjutkan sub-project 1:** langsung invoke skill `writing-plans` dari spec yang sudah ada — tidak perlu brainstorming ulang, spec sudah disetujui.
**Kalau mulai sub-project 2 atau 3:** mulai dari skill `brainstorming` lagi (klasifikasi: architectural — proyek baru/subsystem baru), pakai §6 sebagai titik awal riset, jangan re-explore dari nol kecuali detail di lapangan sudah berubah sejak 2026-09-22.

---

## 4. Sub-project 1 — Ringkasan (detail lengkap ada di spec-nya sendiri)

Menambah `device-sdk-mpos` (adapter mPOS Bluetooth eksternal Newland/Topwise, membungkus lib proprietary `com.lib.device.channel.mpos.*`) dan `device-sdk-factory` (satu titik fan-out, probing runtime: coba EDC built-in dulu via `device-sdk-edcsdk`, baru fallback mPOS) ke abstraksi `device-sdk-api` yang sudah ada. `device-sdk-edcsdk` (6 vendor EDC built-in via `edc-sdk` AAR: PAX/Sunmi/Centerm/Nexgo/Topwise/Szanfu) **tidak dipecah lebih jauh** — keputusan itu sudah pernah dicoba & dibatalkan tim (`docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md`) karena `edc-sdk` sudah menangani multi-vendor internal. Kontrak `device-sdk-api` dapat satu interface opsional baru (`PairableCardReader`) untuk kasus pemilihan reader Bluetooth — tidak ada perubahan pada `provisioning-core`/`signing-core`. Detail penuh (kode connector/factory, aturan boundary, testing strategy, di luar scope) ada di file spec-nya.

**Peringatan penting untuk siapa pun yang lanjut ke implementation plan sub-project 1:** saat spec ini ditulis (2026-09-22), working tree project ini punya perubahan **uncommitted, di luar scope sub-project 1**, yang sudah memperluas `device-sdk-api/CardReader.kt` (dari `waitForCard()` sederhana ke `transact()` + `CardTransactionListener` + `CardTransactionEvent`), plus `cdcp-core/SaleRepository.kt` & `CardPayloadFactory.kt` (tambah dukungan `CardTransactionData`/key EMV), dan file baru `device-sdk-edcsdk/.../EdcSdkCardReader.kt` & `EmvGateway.kt`. Perubahan ini **kemungkinan besar pekerjaan paralel user sendiri** (bukan dibuat oleh sesi brainstorming ini), tapi belum dikonfirmasi ke user maupun di-commit. **Sebelum mengimplementasikan `device-sdk-factory`/`device-sdk-mpos`, cek dulu status `git status` pada `device-sdk-api`/`device-sdk-edcsdk`/`cdcp-core`** — spec sub-project 1 ditulis berdasarkan `CardReader.kt` versi **baru** (yang sudah punya `transact()`), jadi kalau perubahan itu sudah di-commit duluan, semuanya konsisten; kalau di-revert, spec §4/§5 (yang mengasumsikan `CardReader`/`CardTransactionEvent` seperti sekarang) perlu ditinjau ulang.

---

## 5. Sub-project 2 & 3 — pertanyaan terbuka yang perlu dijawab saat mulai brainstorming

**Sub-project 2 (UI parity app-v3):**
- `app-v3` pakai XML/View + Kodein DI + Activity-per-screen (bukan Compose, bukan Navigation-Component). Project `mobile-cashup-payment` saat ini pakai XML + Fragment + Navigation-Component (`nav_provisioning.xml`) untuk alur provisioning. **Perlu diputuskan:** ikut gaya `app-v3` (Activity-per-screen + Kodein) 100%, atau adaptasi ke pola navigasi yang sudah dipakai project ini untuk provisioning (Fragment + Nav-Component), demi konsistensi internal project — ini trade-off "UI sama persis" vs "konsistensi arsitektur project baru".
- `app-v3` py 31 activities + 8 fragments — perlu diprioritaskan/dipecah jadi beberapa sub-project UI kalau ternyata masih terlalu besar untuk satu spec (kemungkinan besar perlu didekomposisi lagi, mis. per flow: CDCP card payment, QRIS, Prepaid, Settings/EOD).

**Sub-project 3 (transaction logic refactor):**
- Redundansi utama yang sudah teridentifikasi (§6.2) yang WAJIB dibenahi, bukan dipindah apa adanya: `EmvViewModel` terduplikasi 3x (app/app-v2/app-v3, masing-masing 578/730/895 baris, sudah divergen), `ReceiptTemplate` terduplikasi 3x (688/919/1068 baris), `PaymentWithCreditDebitActivity` terduplikasi (679 baris v2 vs 1048 baris v3).
- Logic transaksi di `mobile-apps-cashlez` saat ini hidup di layer UI (`ViewModel` di dalam `app-v3/ui/cdcp/paymentcdcp/`), bukan di module core yang reusable (`cdcp-core` di sana cuma DTO/network repository). Project `mobile-cashup-payment` sudah punya `cdcp-core` sendiri dengan crypto/DUKPT layer (`CardPayloadFactory`, `SaleRepository`) — **pertanyaan desain utama:** apakah "EMV state machine" (setara `EmvViewModel`) sebaiknya jadi bagian dari `cdcp-core` project ini (dekat crypto/DUKPT) atau module baru terpisah (`cdcp-transaction-core`?) supaya tidak bergantung pada UI layer sama sekali — ini keputusan arsitektur yang jadi inti "no redundant function" untuk sub-project 3, harus dibahas eksplisit saat brainstorming sub-project 3, jangan diasumsikan.
- Aturan penamaan: hindari tabrakan `Transaction*` (ada di `pos-core` DAN `cdcp-core` untuk konsep beda — cart transaction vs card-auth transaction) — perlu konvensi nama yang jelas beda di project ini sejak awal.

---

## 6. Riset mentah (dari 3 agent eksplorasi paralel, 2026-09-22)

### 6.1 Pola vendor SDK abstraction — `edc-tms-agent` (`D:/gandha_cashup/projects/edc-tms-agent`)

**Modul & tujuan:**
| Modul | Tujuan |
|---|---|
| `tms-protocol` | Wire contract (Command/Result/Envelope, JSON, topics, protocol version). Zero Android. |
| `tms-transport-api` | `TmsTransport` interface — ByteArray di named channel. |
| `tms-device-api` | Kontrak vendor umum: `TmsDevice`, `Capability`, result/info types domain-device. Tidak tahu protokol. |
| `tms-appevent-api` | Kontrak intake payment-app, dipakai bareng repo sibling payment-app. |
| `tms-core` | Orkestrator domain: command dispatch, idempotency, capability negotiation, policy, audit, heartbeat. |
| `tms-storage` | Implementasi Room/DataStore dari storage port `tms-core`. |
| `tms-transport-mqtt` | Satu-satunya modul yang boleh sentuh Paho; implement `TmsTransport` lewat LavinMQ/MQTT. |
| `tms-device-aosp` | Fallback stock-Android (`PackageManager`, `DevicePolicyManager`, telemetry) + helper bersama tiap vendor. |
| `tms-device-{topwise,nexgo,pax,sunmi,centerm,szanfu}` | Satu `TmsDevice` implementation per vendor SDK. |
| `tms-device-factory` | Satu-satunya titik fan-out — resolve adapter vendor yang benar saat runtime. |
| `app` | Foreground service, boot receiver, DI wiring, status UI — tanpa business logic. |

**Kontrak inti** (`tms-device-api/src/main/kotlin/com/lib/tms/device/`):
- `TmsDevice.kt` — interface wajib tiap vendor: `vendor: String`, `connect(context): Boolean` (binding berbasis probe, tidak boleh throw kalau hardware salah), `capabilities(): Set<Capability>`, pembacaan per-domain (`identity/software/paymentStack/telemetry/connectivity/display/location/time/observedPolicy/hardwareIdentity`), dan aksi (`reboot/shutdown/setTime/listApps/installApp/uninstallApp/setAppEnabled/applyLockdown/applyNetwork/updateFirmware`).
- `Capability.kt` — enum yang **diprobe live**, tidak pernah hardcoded dari tabel model, supaya capability yang hilang balik sebagai `UNSUPPORTED` bernama vendor+capability, bukan diam-diam hilang.
- `DeviceOutcome.kt` — sealed `Ok / Unsupported / Failed`, membedakan "vendor tidak bisa" vs "vendor coba tapi gagal".
- `SelfProtection.kt` — interface kedua yang **opsional**, diimplementasi via `as?`, karena tidak semua vendor bisa harden diri sendiri.
- `info/DeviceInfo.kt` — DTO domain nullable tanpa default, supaya tiap adapter sadar melaporkan `null` per field, bukan default diam-diam.

Aturan baku: `tms-device-api` tidak pernah import tipe protokol/JSON — vendor code tetap wire-agnostic.

**Contoh vendor:** PAX (`PaxTmsDevice.kt`) & Topwise (`TopwiseTmsDevice.kt`) sama-sama implement `TmsDevice, SelfProtection`, dependency AAR vendor `implementation`-scoped (tidak pernah `api`), keduanya delegasikan `location()`/`listApps()` ke `aosp: TmsDevice` yang diinject (satu-satunya edge vendor→vendor yang diizinkan), dan bungkus semua panggilan vendor dengan helper (`act`, `actReturning`, dst.) yang mengubah `RemoteException` jadi `DeviceOutcome.Failed`, bukan propagate.

**`tms-device-factory`** (`TmsDeviceFactory.kt`): `Build.MANUFACTURER`/`Build.MODEL` cuma **mengurutkan** daftar `Candidate` (hint substring); seleksi sesungguhnya lewat **probing** — coba `connect()` tiap candidate, yang pertama bind menang, bukan tabel model→class statis. AOSP adapter selalu di-connect duluan (bootstrap permission) dan selalu dicoba terakhir sebagai fallback terjamin. Exception saat build/connect = "vendor ini bilang tidak"; `Error` propagate (stub AIDL yang di-strip adalah cacat build, bukan ketidakhadiran vendor).

**`tms-core`**: `CommandDispatcher.kt` = entry point utama, pipeline tetap `decode → addressing → signature → expiry → idempotency → policy → capability → execute → persist → publish`, didorong `TmsDevice` yang diinject. `SnapshotAssembler.kt` = satu-satunya mapper device-domain→wire schema, merge nilai vendor di atas fallback AOSP, mencatat `sources: "vendor"|"aosp"` per field. `tms-core` cuma depend `tms-protocol` + `tms-device-api` + `tms-transport-api`, tidak pernah vendor SDK langsung.

**Dokumen desain:** `docs/superpowers/specs/2026-09-08-edc-tms-agent-design.md` §5 (diagram hexagonal, tabel tanggung jawab modul, 7 aturan dependency — termasuk "tidak ada modul vendor boleh depend modul vendor lain, satu-satunya cross-edge adalah vendor → `tms-device-aosp`"; "kelas SDK vendor tidak boleh bocor lewat batas modulnya sendiri" — ditegakkan CI task `checkModuleBoundaries`). `docs/adr/001-edc-sdk-consumption.md` — ADR yang membuktikan AAR vendor (dibangun dari repo `edc-sdk` terpisah, dikonsumsi lewat `flatDir { dirs("aarlib") }`) mengekspos jar internal di classpath compile tanpa perlu ubah `edc-sdk`.

### 6.2 `mobile-apps-cashlez` (`D:/gandha_cashup/projects/mobile-apps-cashlez`)

**Modul (`settings.gradle`):**
- App variants: `app` (legacy, sudah di-comment-out dari `settings.gradle`), `app-v2` (legacy, masih dibuild), `app-v3` (**app aktif saat ini**, per `AGENTS.md`).
- `feature/*`: `pos`, `pos-tablet`, `pos-shared`, `cash`, `prepaid`, `BNPL`, `va`, `qris-static`, `cashlezlink`, `ppob`, `promo`, `cnp`.
- Core/shared: `common-core`, `common-general`, `cdcp-core` (layer API card/EMV payment — DTO & repository saja), `qris-core`, `prepaid-core`, `pos-core` (data cart/produk POS), `bridge-api`, `usb-connect`, `external-use`, `custom-component`.

**Arsitektur UI `app-v3`:**
- Toolkit: **XML/View murni** (`viewBinding true`, `dataBinding = true`), nol `@Composable`.
- Navigasi: **tanpa Navigation-Component** — Activity-per-screen via `Intent`, dideklarasikan langsung di `AndroidManifest.xml` (31 activities + 8 fragments di bawah `ui/`). Bottom-nav-style (home/simple/lock mode) pakai Fragment di dalam `HomeActivity`.
- DI: **Kodein** (`org.kodein.di`) — modul di `app-v3/src/main/java/com/cz/app/di/` (`ConfigModule.kt`, `RepositoryModule.kt`, `ServiceModule.kt`, `ViewModelModule.kt`, `PrepaidModule.kt`, `RegisterMpos.kt`).
- Alur/layar utama: Splash → Login → Home (Simple/Lock mode fragment) → CDCP card payment (`ui/cdcp/{paymentcdcp,installment,inputpin,voidlist,settlement,settlebatchhistory,paymenthistory,resultcdcp}`), QRIS (`ui/qris/*`: generate, ewallet, history, pending, void, settlelist), Prepaid MTI top-up/settlement, EOD/audit report, Settings, Environment switcher, Reprint deep link, Cashcare, History, layar debug Serial-USB/Swing.
- `RegisterMpos.kt` mewire vendor mPOS SDK (**Newland, Topwise**) ke `BrandRegistry` (`com.lib.device.channel.mpos`, AAR di `aarlib/`) — device abstraction ini **terpisah total** dari `device-sdk-api`/`device-sdk-edcsdk` project ini.

**app-v3 vs app-v2 vs app:** Struktural nyaris identik (skeleton package sama: `data/device/di/job/manager/model/ui/util/worker`, gaya DI Kodein sama). app-v3 cuma nambah beberapa package UI baru (`ui/cashcare`, `ui/history`, `ui/prepaid`, `ui/reprint`) di atas app-v2. **Ini fork, bukan layer wiring bersih** — kelas payment inti di-copy-paste dengan edit divergen per varian:
- `PaymentWithCreditDebitActivity.kt`: 679 baris (v2) vs 1048 baris (v3).
- `EmvViewModel.kt`: 578 (`app`), 730 (`app-v2`), 895 (`app-v3`) baris — 3 salinan state machine EMV yang sama.
- `ReceiptTemplate.kt`: 688 (`app`), 919 (`app-v2`), 1068 (`app-v3`) baris — 3 salinan logic format struk.

**Kelas transaksi kunci** (semua di `app-v3/src/main/java/com/cz/app/ui/cdcp/paymentcdcp/` kecuali disebut lain):
- `EmvViewModel.kt` (895 baris) — implement `TransactionResponse`/`DetectReaderListener` device-SDK langsung; handle card read, applet select, PIN entry, signature capture, online-authorization (`onOnlineProcess`), decrypt EMV data, reversal, TLV logging. **Ini engine transaksi sesungguhnya, tertanam di layer UI**, bukan module terpisah.
- `EmvPromoViewModel.kt` (423 baris) — varian promo/cicilan dari flow yang sama.
- `SaleCreditDebitViewModel.kt`, `PaymentWithCreditDebitActivity.kt` (1048 baris) — glue layar + request building.
- `EmvState.kt`, `EmvResult.kt`, `AuthEnum.kt`, `AppletListDialog.kt` — state/enum pendukung.
- Layer API (`cdcp-core/src/main/java/com/cdcp/core/`): `repositories/CardPayRepository.kt` (762 baris — sale/void/settlement/reprint), `services/CDCPService.kt` (interface Retrofit-style), model DTO (`Transaction.java`, `TransactionReversal.java`, `TransactionError.java`, `EmvInfo.kt`, `CardRoutingResponse.kt`, `Settlement*.java`), request (`CreditDebitRequest.kt`, `PaymentRequest.java`, `VoidRequest.kt`).
- Settlement/receipt/result: `ui/cdcp/settlement/{SettlementViewModel,SettlementActivity,SettlementAdapter}.kt`, `ui/cdcp/resultcdcp/{ResultCreditDebitViewModel,ResultCreditDebitActivity}.kt`, `util/ReceiptTemplate.kt` (1068 baris, `object` tunggal untuk semua format struk card/QRIS/prepaid).
- POS (cart) — concern terpisah dari card transaction: `feature/pos/.../ui/{payment,transaction,result,product,stock,category}` + `pos-core/.../data/{repositories,local,network}`. `feature/pos-shared/.../transaction/{TransactionDateParser,TransactionFilter}.kt` cuma utility tipis, bukan payment engine.

**Bau redundansi (untuk sub-project 3):**
1. `EmvViewModel` terduplikasi 3x dengan edit divergen — target refactor terbesar.
2. `ReceiptTemplate` terduplikasi 3x.
3. `PaymentWithCreditDebitActivity` terduplikasi antara v2/v3, UI+logic tercampur (Sentry logging, instance Kodein, worker/print logic langsung di Activity).
4. Payment orchestration hidup di **layer UI app module**, bukan di `cdcp-core`/setara `feature/pos` — `cdcp-core` di sana cuma DTO/network, business logic tidak bisa dipakai ulang tanpa ikut narik Activity/ViewModel.
5. `feature/pos` (cart) vs `cdcp-core`/`app-v3 ui/cdcp` (card auth) — dua concern terpisah yang masuk akal terpisah, tapi penamaan `Transaction*` muncul di keduanya, berisiko bingung saat konsolidasi.

**Dokumentasi terkait:** tidak ada README app-v3-spesifik atau spec flow POS-transaction. `docs/PERHITUNGAN_TAX_DAN_SERVICE_CHARGE.md` (459 baris, ID) — urutan hitung subtotal/tax/service-charge/cash-rounding untuk cart POS (bukan card-auth). Lainnya (`docs/deeplink-doc.md`, `MQTT_BROKER_CONFIG.md`, `GRAYLOG_LOGGING.md`, `MIGRASI_TOKEN_KE_JWT.md`, `BACKEND_API_SPEC_NEW_ENDPOINTS.md`) — infra/auth, bukan arsitektur transaksi. Tidak ada dokumen yang menjelaskan state machine EMV/CDCP — cuma bisa ditemukan dengan baca `EmvViewModel.kt` langsung.

### 6.3 `mobile-cashup-payment` — state saat ini (project ini sendiri)

**Modul (`settings.gradle.kts`):**
| Modul | Tujuan |
|---|---|
| `common-core` | Plumbing network bersama: `ApiEnvelope`/`ApiResult`/`ApiError`, factory Retrofit, safe API-call wrapper, request logging. Pure Kotlin/JVM. |
| `device-sdk-api` | Kontrak vendor-agnostic untuk bicara ke terminal EDC (card read, key injection, printer, scanner, serial number). Pure Kotlin/JVM, tanpa Android deps. |
| `signing-core` | Ed25519 request signing (`DeviceSigner`, `Ed25519RequestSigner`, OkHttp `SigningInterceptor`) untuk skema canonical-string/`X-Signature`. |
| `device-sdk-edcsdk` | Satu-satunya implementasi konkret `device-sdk-api`, membungkus AAR SDK multi-vendor asli (lihat bawah). |
| `provisioning-core` | Ceremony provisioning gaya TR-34 (identity RSA, signing Ed25519, install DUKPT), state persistence, journal/audit. |
| `cdcp-core` | Sisi "sale": pembangunan payload kartu, crypto DUKPT/pin-block, repository/DTO HTTP sale. |
| `app` | Shell aplikasi Android: Activity/Fragment/nav graph, DI container, glue QR scan. |

**`device-sdk-api`** (package `com.cashup.devicesdk`, semua pure Kotlin, tanpa Android):
- `DeviceSdk` — handle vendor top-level: `vendorId`, `capabilities: Set<Capability>`, `cardReader`/`printer`/`scanner` opsional.
- `Capability` — `CARD_READ`, `PRINT`, `SCAN_QR`.
- `CardReader` — `transact(request, listener): CardReadResult` + `cancel()`, `CardTransactionEvent` sealed (Connecting → WaitingForCard → CardDetected → PinRequested/Progress → Authorizing → Completing), `CardTransactionListener.authorize()` suspend hook balik ke host app. **(Lihat peringatan §4 — versi ini kemungkinan hasil kerja paralel yang belum dikonfirmasi/commit.)**
- `Scanner.scanQr(timeoutMillis): String?`. `Printer.print(ReceiptContent): PrintResult`. `SerialNumberProvider.serialNumber(): String?`.
- `TerminalKeyInstaller`/`TerminalKeyMaterial`/`DukptKeyProvider` — kontrak injeksi key DUKPT, melaporkan `KeyBacking` (`VENDOR_SECURE_MODULE` vs `TEE_VAULT_ONLY`) per purpose. Gap terdokumentasi eksplisit: `wipe()` tidak bisa hapus IPEK yang sudah terbakar di modul aman vendor (AAR vendor cuma expose `writeIPEK`, tanpa delete).

**`device-sdk-edcsdk`**: bukan satu vendor — wrapper tipis (`EdcSdkCardReader`, `EmvGateway`/`RealEmvGateway`, `EdcSdkDukptKeyProvider`, `EdcSdkSerialNumberProvider`, `EdcSdkTerminalKeyInstaller`, `EdcSdkScanner`, `KeyManagerGateway`) di sekitar SDK internal **`edc-sdk`** (`D:/gandha_cashup/projects/edc-sdk`), dikonsumsi sebagai AAR prebuilt di `aarlib/` (flatDir): `core-release_1.0.63`, `logger-release_1.0.2`, plus core per-vendor **PAX, Sunmi, Centerm, Nexgo, Topwize, Szanfu** (7 AAR total). Deteksi device didelegasikan ke `SDKManager.autoDetectDevice` milik SDK vendor sendiri. `aarlib/README.md` mencatat **Feitian, Urovo, Newland, Tianyu tidak punya AAR & implementasi `SystemKey`** di `edc-sdk` — tidak didukung hari ini (**ini sebab Newland harus lewat jalur mPOS terpisah di sub-project 1**).

Catatan historis penting: `docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md` (plan yang **dibatalkan**) awalnya berniat memecah jadi 6 modul adapter Kotlin terpisah (`device-sdk-pax`, `-sunmi`, dst., meniru `tms-agent`) tapi dibatalkan setelah sadar AAR `edc-sdk` sudah melakukan deteksi/injeksi/EMV multi-vendor secara internal.

**`provisioning-core`**: ceremony provisioning gaya TR-34 selaras kontrak `edc-mobile` — keypair identity RSA (dual-purpose decrypt+sign, TEE-preferred via `RsaKeyStore`), sertifikat X.509 self-signed (`SelfSignedCertificate`), keypair signing Ed25519 (`Ed25519KeyStore`), QR-redeem → fetch key-package (ditandatangani Ed25519, unwrap hybrid RSA-OAEP+AES-GCM via `PackageUnwrapper`/`RsaUnwrapper`/`Tr34KeyTokenParser`) → install DUKPT → activate, state persistence dua-fase (`identity` vs `dukpt`, dipisah supaya kegagalan parsial tidak menghancurkan identity device), journal audit debug-only (`Evidence`, `ProvisioningJournal`).

**`signing-core`**: cuma concern request-signing — `DeviceSigner` baca identity device, `Ed25519RequestSigner` bikin signature canonical-string, `SigningInterceptor` menempel header `X-Timestamp`/`X-Nonce`/`X-Signature`/`X-Device-Id` ke request OkHttp keluar.

**`app` module — kematangan UI**: Tipis. `MainActivity` shell 10 baris yang host nav-graph (`nav_provisioning.xml`) dengan fragment XML: `GateFragment`, `ScanQrFragment`, `ProcessingFragment`, `ResultFragment` (alur provisioning), plus `SaleFragment`/`SaleViewModel` (~70 baris masing-masing). README menyatakan eksplisit: layar sale pakai **katalog kartu test** (`cdcp-core/CardCatalog.kt`, 30 baris) — bukan card read fisik sungguhan. **UI real cuma ada untuk ceremony provisioning; layar sale/transaksi masih demo/stub.**

**Scope inisiatif saat ini** (`docs/superpowers/specs/2026-09-17-provisioning-edc-mobile-adoption-design.md`): menjadikan project sibling `edc-mobile` (terbukti jalan lawan backend `corepayment`) sebagai otoritas penuh kontrak wire provisioning. Di luar scope eksplisit: varian protokol TR34_2019, journey "J2" penuh (menu device settings/re-provisioning/deactivation UI), field `kdhCertificateChain`/`keyBlock`. Tidak disentuh spec itu: adapter vendor (`EdcSdkScanner`, `EdcSdkTerminalKeyInstaller`, `KeyManagerGateway`), `signing-core`, layar UI selain `ResultFragment`.

**Root docs**: tidak ada `CLAUDE.md`. `README.md` (Bahasa Indonesia) dokumentasikan alur API end-to-end (qr-redeem → fetch package → install → activate → sale) dan tanggung jawab modul satu paragraf masing-masing, plus catatan "test-card only" yang sama, dan perintah Gradle verifikasi lokal.
