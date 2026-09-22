# mobile-cashup-payment

Android EDC app dengan UI XML dan modul terpisah untuk provisioning serta sale CDCP.

## Alur API

1. `POST v1/terminal-key-provisioning/qr-redeem` tanpa device signature. Body mengikuti `edc-mobile`: `qrToken`, nomor seri, public key RSA dan Ed25519, tiga purpose DUKPT, serta sertifikat device.
2. Simpan `deviceId` dari backend. Jika `dukptProvisioningRequired=false`, berhenti tanpa mengganti key DUKPT yang ada.
3. `GET v1/terminal-key-provisioning/orders/{orderId}/package` dengan `X-Activation-Token` dan device signature. Verifikasi signature paket Ed25519, buka AES key dengan RSA, dekripsi payload AES-GCM, lalu periksa KCV.
4. Install TRACK, AMOUNT, PIN lewat `device-sdk-edcsdk`; `POST .../activate` dengan KCV dan proof RSA. Simpan state DUKPT setelah aktivasi berhasil.
5. `POST v1/cdcp/sales` dengan payload TRACK/AMOUNT/PIN terenkripsi DUKPT, signature device, dan `Idempotency-Key` per aksi pembayaran. Retry mengirim body dan key yang sama selama proses masih hidup.

`app` hanya menjadi composition root dan navigasi. `feature-card-payment` memiliki UI serta state transaksi secara terisolasi; `provisioning-core` menangani provisioning; `cdcp-core` menangani payload DUKPT dan HTTP sale; `device-sdk-api`, `device-sdk-edcsdk`, serta `device-sdk-mpos` menangani kontrak dan adapter vendor; `device-sdk-factory` menjadi satu-satunya titik pemilihan vendor; `signing-core` menandatangani request; `common-core` menyediakan kontrak jaringan.

Layar pembayaran kartu mengikuti desain `mobile-apps-cashlez/app-v3` dan memakai pembacaan kartu fisik dari SDK vendor. AID, contactless AID, CAPK production, dan tag profile diadopsi dari assets `app-v3`. Data TRACK, AMOUNT, PIN block, dan ICC diproses dengan key DUKPT hasil provisioning; ICC memakai purpose TRACK karena kontrak provisioning hanya menyediakan TRACK/AMOUNT/PIN. Fake reader hanya berada di test fixtures dan tidak ikut runtime produksi.

Validasi akhir terhadap backend dan terminal fisik tetap diperlukan sebelum rilis produksi.

Verifikasi lokal: `gradlew.bat :device-sdk-edcsdk:testDebugUnitTest :device-sdk-mpos:testDebugUnitTest :device-sdk-factory:testDebugUnitTest :cdcp-core:testDebugUnitTest :feature-card-payment:testDebugUnitTest :app:assembleDebug checkModuleBoundaries`.
