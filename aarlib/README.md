# `aarlib/` — biner SDK vendor

Berkas `.aar` di sini adalah hasil build repo `edc-sdk`
(`D:\gandha_cashup\projects\edc-sdk`), disalin dari
`D:\gandha_cashup\projects\edc-tms-agent\aarlib`. Repo ini **tidak** membangunnya
dan biner itu **tidak** di-commit.

| Berkas | Dipakai oleh |
|---|---|
| `core-release_1.0.63-compat.aar` | `:device-sdk-edcsdk` — `SDKManager`, `KeyManager`, `BaseSystemKey` |
| `logger-release_1.0.2.aar` | `:device-sdk-edcsdk` — dependensi `core` |
| `pax-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `sunmi-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `centerm-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `nexgo-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `topwize-release-core_1.0.63-compat.aar` | `:device-sdk-edcsdk` |
| `szanfu-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `other-release-core_1.0.63.aar` | `:device-sdk-mpos` — `DeviceConnectionManager`, `Channel`, `BrandRegistry`, `MposReaderDevice` |
| `newland-mpos-release-core_1.0.63.aar` | `:device-sdk-mpos` — `NewlandBlueHelper` |
| `topwise-mpos-release-core_1.0.63.aar` | `:device-sdk-mpos` — `TopwiseBlueHelper` |

## Memperbarui

Build ulang di `edc-sdk` lalu salin hasilnya ke sini. Naikkan nomor versi di
`device-sdk-edcsdk/build.gradle.kts` bersamaan — nama berkas adalah koordinat
dependensinya.

Feitian, Urovo, dan Tianyu tidak punya AAR di sini dan tidak punya implementasi
`SystemKey` di `edc-sdk` — tidak didukung sebagai EDC terminal built-in. Newland
**hanya** didukung lewat mPOS Bluetooth eksternal (`:device-sdk-mpos`), bukan
lewat `:device-sdk-edcsdk` — `edc-sdk` tidak punya modul `newland` (terminal
built-in) untuk vendor ini, hanya `newland-mpos`.

## Berkas `-compat` (core dengan `CardData` kompatibel biner)

`core-release_1.0.63-compat.aar` dan `topwize-release-core_1.0.63-compat.aar` di-build dari `edc-sdk`
setelah `CardData` diubah: konstruktor dan `copy` kembali bersignature **tujuh field**, sedangkan
`pinKsn` hidup di badan kelas dan ikut tersalin oleh `copy`. Tanpa itu, `anfu-mpos-debug-core_1.0.68`
(dan semua AAR vendor lain yang dikompilasi sebelum `pinKsn` ditambahkan) crash dengan
`NoSuchMethodError: CardData.copy$default`. API publik core ini identik dengan `core-release_1.0.65`
(set yang teruji bersama `anfu-bridge`) ditambah `getPinKsn/setPinKsn`. Perubahan sumbernya ada di
`edc-sdk` (`core/.../emv/CardData.kt`, `topwize/.../emv/EmvConfiguration.kt`) dan belum di-commit.

