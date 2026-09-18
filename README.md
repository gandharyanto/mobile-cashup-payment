# mobile-cashup-payment

Android EDC app dengan UI XML dan modul terpisah untuk provisioning serta sale CDCP.

## Alur API

1. `POST v1/terminal-key-provisioning/qr-redeem` tanpa device signature. Body mengikuti `edc-mobile`: `qrToken`, nomor seri, public key RSA dan Ed25519, tiga purpose DUKPT, serta sertifikat device.
2. Simpan `deviceId` dari backend. Jika `dukptProvisioningRequired=false`, berhenti tanpa mengganti key DUKPT yang ada.
3. `GET v1/terminal-key-provisioning/orders/{orderId}/package` dengan `X-Activation-Token` dan device signature. Verifikasi signature paket Ed25519, buka AES key dengan RSA, dekripsi payload AES-GCM, lalu periksa KCV.
4. Install TRACK, AMOUNT, PIN lewat `device-sdk-edcsdk`; `POST .../activate` dengan KCV dan proof RSA. Simpan state DUKPT setelah aktivasi berhasil.
5. `POST v1/cdcp/sales` dengan payload TRACK/AMOUNT/PIN terenkripsi DUKPT, signature device, dan `Idempotency-Key` per aksi pembayaran. Retry mengirim body dan key yang sama selama proses masih hidup.

`app` berisi navigasi dan tampilan XML; `provisioning-core` menangani provisioning; `cdcp-core` menangani payload dan HTTP sale; `device-sdk-api` serta `device-sdk-edcsdk` menangani vault dan adapter vendor; `signing-core` menandatangani request; `common-core` menyediakan kontrak jaringan.

Layar sale saat ini memakai katalog **kartu uji** seperti project `edc-mobile copy`. Integrasi pembacaan kartu fisik belum tersedia pada adapter SDK project ini, sehingga layar tersebut belum menjadi alur kartu produksi. Validasi akhir terhadap backend dan terminal fisik juga masih diperlukan.

Verifikasi lokal: `gradlew.bat :provisioning-core:testDebugUnitTest :cdcp-core:testDebugUnitTest :device-sdk-edcsdk:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug --offline`.
