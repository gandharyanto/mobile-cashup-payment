# Device SDK — Abstraksi Multi-Vendor ala TMS (EDC Built-in + mPOS Bluetooth) — Design Spec

**Tanggal:** 2026-09-22
**Status:** disetujui, siap dijadikan implementation plan
**Bagian dari inisiatif lebih besar:** adopsi UI `app-v3` (`mobile-apps-cashlez`) + perbaikan logic transaksi dari `mobile-apps-cashlez` tanpa function redundan. Sub-project ini adalah **yang pertama** dari tiga: vendor SDK abstraction → (berikutnya, spec terpisah) UI parity dengan app-v3 → (berikutnya, spec terpisah) refactor transaction logic. Dua sub-project berikutnya **tidak** dibahas di sini.

---

## 1. Konteks & keputusan yang membentuk spec ini

Project ini (`mobile-cashup-payment`) sudah punya `device-sdk-api` (kontrak vendor-agnostic) + `device-sdk-edcsdk` (satu implementasi, membungkus AAR internal `edc-sdk` yang sendirinya sudah multi-vendor: PAX, Sunmi, Centerm, Nexgo, Topwise, Szanfu, lewat `SDKManager.autoDetectDevice`).

Tim sebelumnya sempat merencanakan `docs/superpowers/plans/2026-09-16-device-sdk-vendor-adapters.md` — memecah `device-sdk-edcsdk` jadi modul per vendor mengikuti pola `edc-tms-agent` (`tms-device-pax`, `tms-device-sunmi`, dst.) — lalu **membatalkannya** setelah sadar `edc-sdk` AAR sudah melakukan deteksi & fan-out multi-vendor itu sendiri secara internal. Memecahnya lebih jauh di level project ini tidak menambah nilai.

Spec ini **tidak membalik keputusan itu**. Riset untuk UI-parity dengan `app-v3` (`mobile-apps-cashlez`) menemukan fakta baru yang mengubah kalkulasi: `app-v3` memakai **dua keluarga device yang sungguh berbeda** —

1. **EDC terminal built-in** (PAX/Sunmi/Centerm/Nexgo/Topwise/Szanfu) — sudah dicover `device-sdk-edcsdk` hari ini.
2. **mPOS reader Bluetooth eksternal** (Newland, Topwise mPOS) — dipakai `app-v3` lewat lib proprietary terpisah (`com.lib.device.channel.mpos.BrandRegistry`, AAR `blue-reader-debug` dkk) yang **tidak** dicover `edc-sdk`/`device-sdk-edcsdk` sama sekali.

Karena ini benar-benar dua stack SDK vendor yang independen (bukan satu AAR yang sudah menangani keduanya), pola modul-per-keluarga-vendor ala TMS sekarang punya alasan nyata: mengisolasi AAR proprietary mPOS di modulnya sendiri, dan menambahkan satu titik seleksi runtime (`device-sdk-factory`) yang tidak ada hari ini karena selama ini cuma ada satu implementasi (`device-sdk-edcsdk`).

**Yang TIDAK dipecah lebih jauh:** 6 vendor di dalam `edc-sdk` (PAX/Sunmi/Centerm/Nexgo/Topwise/Szanfu) — `device-sdk-edcsdk` tetap satu modul untuk itu, keputusan pembatalan 16 September tetap berlaku untuk cakupannya.

---

## 2. Ringkasan perubahan

| Aspek | Sekarang (`main`) | Spec ini |
|---|---|---|
| Modul implementasi `device-sdk-api` | Hanya `device-sdk-edcsdk` (EDC built-in, 6 vendor via `edc-sdk`) | + `device-sdk-mpos` (baru, Bluetooth eksternal Newland/Topwise mPOS) |
| Titik seleksi vendor | Tidak ada — consumer langsung construct `device-sdk-edcsdk` | + `device-sdk-factory` (baru) — satu-satunya titik fan-out, probing runtime |
| Kontrak `device-sdk-api` | `DeviceSdk`/`CardReader`/`Printer`/`Scanner`/`Capability` (tidak berubah) | + `PairableCardReader`/`PairedDeviceInfo` (interface opsional baru, dicek via `as?`, hanya untuk reader yang butuh pemilihan device eksplisit) |
| `provisioning-core` | Wiring langsung ke `EdcSdkTerminalKeyInstaller` | **Tidak berubah** — lihat §5 |
| Boundary enforcement | Tidak ada Gradle check khusus | + task `checkModuleBoundaries` (ala `tms-core`'s `checkModuleBoundaries`) |

---

## 3. Arsitektur modul

```
device-sdk-api          (tidak berubah, +PairableCardReader)
   ^        ^        ^
   |        |        |
device-sdk-edcsdk   device-sdk-mpos     <- implementasi, saling TIDAK boleh depend
   ^        ^
   |        |
   device-sdk-factory   <- SATU-SATUNYA modul yang boleh depend ke keduanya
        ^
        |
   cdcp-core / app       <- depend HANYA ke device-sdk-api + device-sdk-factory,
                             tidak pernah import tipe internal AAR vendor manapun
```

- **`device-sdk-edcsdk`** (sudah ada, tidak diubah selain menambah satu fungsi baru — lihat §4): implementasi EDC terminal built-in, membungkus AAR `edc-sdk`.
- **`device-sdk-mpos`** (baru, Android library): implementasi mPOS Bluetooth eksternal, membungkus AAR proprietary `com.lib.device.channel.mpos.*` (dipindah dari `mobile-apps-cashlez/aarlib` ke `aarlib/` project ini). Semua dependency AAR `implementation`-scoped, tidak pernah `api` — sama seperti aturan boundary `tms-device-pax`/`tms-device-topwise` di `edc-tms-agent`.
- **`device-sdk-factory`** (baru, Android library): satu-satunya titik fan-out runtime. Depends on `device-sdk-api`, `device-sdk-edcsdk`, `device-sdk-mpos`.

Aturan boundary (ditegakkan lewat Gradle task `checkModuleBoundaries`, §6):
1. Hanya `device-sdk-factory` boleh depend ke `device-sdk-edcsdk` **dan** `device-sdk-mpos` sekaligus.
2. `device-sdk-edcsdk` dan `device-sdk-mpos` tidak boleh saling depend.
3. `cdcp-core`/`app` tidak boleh import tipe internal AAR vendor manapun, hanya lewat `device-sdk-api`/`device-sdk-factory`.

---

## 4. Kontrak — apa yang berubah di `device-sdk-api`

**Tidak berubah:** `DeviceSdk`, `Capability`, `CardReader`, `CardTransactionEvent`, `Printer`, `Scanner`, `SerialNumberProvider`, `TerminalKeyInstaller`, `DukptKeyProvider`, `TerminalKeyMaterial` — semua persis seperti sekarang. `DeviceSdk` **tidak** mendapat method `connect()`; modul ini tetap murni Kotlin/JVM tanpa Android `Context`, karena logika probing didorong ke connector di tiap modul implementasi (§5), bukan ke kontrak bersama.

**Baru — satu interface opsional**, untuk kasus mPOS yang butuh pemilihan reader fisik sebelum bisa `transact()` (EDC built-in tidak butuh ini sama sekali):

```kotlin
package com.cashup.devicesdk

data class PairedDeviceInfo(val id: String, val name: String)

interface PairableCardReader {
    fun pairedDevices(): List<PairedDeviceInfo>
    suspend fun selectDevice(id: String): Boolean
}
```

`MposCardReader` (di `device-sdk-mpos`) mengimplementasikan `CardReader` **dan** `PairableCardReader`. `EdcSdkCardReader` (built-in) tidak. Consumer mengecek lewat `as?` — pola yang sama seperti `SelfProtection` opsional di `edc-tms-agent`:

```kotlin
val reader = deviceSdk.cardReader
val pairable = reader as? PairableCardReader   // non-null → tampilkan layar pilih reader
```

---

## 5. Connector & Factory — seleksi runtime

Setiap modul implementasi mengekspos satu fungsi connector top-level (bukan lewat interface bersama — factory langsung mengimpor kelas konkret tiap modul, sama seperti `tms-device-factory` mengimpor `PaxTmsDevice()`/`TopwiseTmsDevice()` langsung):

```kotlin
// device-sdk-edcsdk
object EdcSdkConnector {
    fun tryConnect(context: Context): DeviceSdk?   // null = SDKManager.autoDetectDevice tidak menemukan device ini
}

// device-sdk-mpos
object MposConnector {
    fun tryConnect(context: Context): DeviceSdk?   // null = adapter Bluetooth tidak tersedia di device ini
}
```

```kotlin
// device-sdk-factory
class DeviceSdkFactory(private val context: Context) {
    private val candidates: List<suspend () -> DeviceSdk?> = listOf(
        { EdcSdkConnector.tryConnect(context) },
        { MposConnector.tryConnect(context) },
    )

    suspend fun connect(): DeviceSdk {
        for (candidate in candidates) {
            val result = runCatching { candidate() }.getOrNull()
            if (result != null) return result
        }
        throw NoDeviceSdkAvailableException()
    }
}
```

**Urutan probing sengaja berjenjang, bukan race murni** (beda dari `tms-device-factory` yang murni "candidate pertama yang connect menang"): EDC built-in dicoba lebih dulu karena deteksinya cepat & deterministik per hardware. mPOS baru dicoba kalau built-in tidak ada. "Berhasil connect" untuk mPOS **bukan** berarti sudah ada reader Bluetooth terpasang — itu cuma berarti adapter Bluetooth + izin runtime tersedia di device ini. Binding ke reader fisik terjadi belakangan, dipicu UI lewat `PairableCardReader` (§4), bukan di dalam `MposConnector.tryConnect()` — connector tidak boleh meminta izin runtime atau membuka dialog pairing sendiri.

**Alur pemakaian (tidak mengubah `transact()`/`CardTransactionEvent` sama sekali):**
1. `DeviceSdkFactory(context).connect()` dipanggil sekali di awal alur sale → satu `DeviceSdk`.
2. `deviceSdk.cardReader as? PairableCardReader` non-null → tampilkan layar pilih-reader (`pairedDevices()` → user pilih → `selectDevice(id)`, di sinilah bonding Bluetooth sungguhan terjadi, boleh gagal/retry). Null → langsung `transact()`.

---

## 6. Error handling & boundary enforcement

- **`Exception` vs `Error`**: `runCatching` di factory scoped ke `Exception`. Kegagalan bind (`Exception`) berarti "vendor ini bilang tidak, coba kandidat berikutnya". `Error` (mis. `NoClassDefFoundError` karena AAR salah dikonfigurasi) **harus tetap propagate** dan meledakkan build/CI, bukan diam-diam dianggap "vendor tidak ada" — meniru aturan yang sama di `tms-device-factory`.
- **Tidak ada kandidat berhasil**: `DeviceSdkFactory.connect()` throw `NoDeviceSdkAvailableException`. Penanganan di UI (pesan ke user, retry, dsb.) adalah scope sub-project UI berikutnya, bukan spec ini.
- **Module boundary check**: Gradle task `checkModuleBoundaries` (dependency graph assertion, dijalankan di CI) menegakkan tiga aturan §3.

---

## 7. Testing

- `device-sdk-edcsdk` dan `device-sdk-mpos`: unit test masing-masing dengan AAR vendor di-fake/mock, tidak butuh hardware fisik.
- `device-sdk-factory`: diuji dengan fake connector (bukan `EdcSdkConnector`/`MposConnector` asli) untuk memverifikasi urutan probing dan pembedaan `Exception` (lanjut ke kandidat berikutnya) vs `Error` (propagate) — tidak perlu compile AAR vendor sungguhan di classpath test.

---

## 8. Di luar scope

- **`TerminalKeyInstaller`/`DukptKeyProvider` (provisioning DUKPT) — tidak disentuh sama sekali.** Kedua interface itu top-level, terpisah dari `DeviceSdk`; `provisioning-core` sudah wiring langsung ke `EdcSdkTerminalKeyInstaller` dari `device-sdk-edcsdk`, bukan lewat `DeviceSdk.capabilities`. DUKPT key injection ke secure module hanya relevan untuk EDC built-in — mPOS reader eksternal tidak ikut alur provisioning ini. Tidak ada perubahan di `provisioning-core`.
- **Memecah `device-sdk-edcsdk` lebih jauh per vendor internal** (PAX/Sunmi/Centerm/Nexgo/Topwise/Szanfu) — keputusan pembatalan 16 September tetap berlaku; `edc-sdk` AAR sudah menangani itu.
- **UI pemilihan reader** (layar pairing mPOS) dan **UI transaksi** secara umum — didesain di sub-project UI-parity app-v3 berikutnya. Spec ini hanya menyediakan kontraknya (`PairableCardReader`).
- **Refactor logic transaksi dari `mobile-apps-cashlez`** (`EmvViewModel`, `ReceiptTemplate`, dsb.) — sub-project terpisah berikutnya.
- **Vendor di luar cakupan `edc-sdk` dan mPOS Newland/Topwise** (Feitian, Urovo, Tianyu — dicatat tidak dicover `edc-sdk` per `aarlib/README.md` project ini) — tidak diminta, tidak ditambahkan.

---

## 9. Konsekuensi terhadap kode yang sudah ada

| Berkas/Modul | Nasib |
|---|---|
| `device-sdk-api/.../DeviceSdk.kt`, `CardReader.kt`, dll. | **Tidak berubah** |
| `device-sdk-api/.../PairableCardReader.kt` | **Baru** |
| `device-sdk-edcsdk/.../EdcSdkConnector.kt` | **Baru** — fungsi `tryConnect(context)`, tidak mengubah `EdcSdkCardReader` dkk yang sudah ada |
| `device-sdk-mpos/` (seluruh modul) | **Baru** |
| `device-sdk-factory/` (seluruh modul) | **Baru** |
| `settings.gradle.kts` | Tambah `:device-sdk-mpos`, `:device-sdk-factory` |
| `aarlib/` | Tambah AAR proprietary mPOS (dipindah dari `mobile-apps-cashlez/aarlib`) |
| `build.gradle.kts` (root) | Tambah task `checkModuleBoundaries` |
| `provisioning-core`, `signing-core`, `cdcp-core`, `app` | **Tidak berubah** oleh spec ini (consumer `app`/`cdcp-core` migrasi ke `DeviceSdkFactory` adalah bagian dari implementation plan, tapi tidak mengubah logic transaksi/provisioning yang sudah ada) |
