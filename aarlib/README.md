# `aarlib/` — biner SDK vendor

Berkas `.aar` di sini adalah hasil build repo `edc-sdk`
(`D:\gandha_cashup\projects\edc-sdk`), disalin dari
`D:\gandha_cashup\projects\edc-tms-agent\aarlib`. Repo ini **tidak** membangunnya
dan biner itu **tidak** di-commit.

| Berkas | Dipakai oleh |
|---|---|
| `core-release_1.0.63.aar` | `:device-sdk-edcsdk` — `SDKManager`, `KeyManager`, `BaseSystemKey` |
| `logger-release_1.0.2.aar` | `:device-sdk-edcsdk` — dependensi `core` |
| `pax-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `sunmi-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `centerm-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `nexgo-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `topwize-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |
| `szanfu-release-core_1.0.63.aar` | `:device-sdk-edcsdk` |

## Memperbarui

Build ulang di `edc-sdk` lalu salin hasilnya ke sini. Naikkan nomor versi di
`device-sdk-edcsdk/build.gradle.kts` bersamaan — nama berkas adalah koordinat
dependensinya.

Feitian, Urovo, Newland, dan Tianyu tidak punya AAR di sini dan tidak punya
implementasi `SystemKey` di `edc-sdk`.
