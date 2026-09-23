# mobile-cashup-payment

Android EDC app dengan UI XML dan modul terpisah untuk provisioning serta sale CDCP.

## Alur API

1. `POST v1/terminal-key-provisioning/qr-redeem` tanpa device signature. Body mengikuti `edc-mobile`: `qrToken`, nomor seri, public key RSA dan Ed25519, empat purpose DUKPT (`TRACK`, `AMOUNT`, `PIN`, `EMV`), serta sertifikat device.
2. Simpan `deviceId` dari backend. Jika `dukptProvisioningRequired=false`, berhenti tanpa mengganti key DUKPT yang ada.
3. `GET v1/terminal-key-provisioning/orders/{orderId}/package` dengan `X-Activation-Token` dan device signature. Verifikasi signature paket Ed25519, buka AES key dengan RSA, dekripsi payload AES-GCM, lalu periksa KCV.
4. Install TRACK, AMOUNT, PIN, EMV lewat `device-sdk-edcsdk`; `POST .../activate` dengan KCV dan proof RSA. Simpan state DUKPT setelah aktivasi berhasil.
5. `POST v1/cdcp/sales` dengan payload TRACK/AMOUNT/PIN/ICC terenkripsi DUKPT, signature device, dan `Idempotency-Key` per aksi pembayaran. Retry mengirim body dan key yang sama selama proses masih hidup.

`app` menjadi composition root, navigasi, dan shell Home (bottom navigation 4 tab — Home/History/Fitur/Device — meniru `app-v3` secara visual; tab Home menampilkan `feature-card-payment`, tab lain masih placeholder). `feature-card-payment` memiliki UI serta state transaksi secara terisolasi; `provisioning-core` menangani provisioning; `cdcp-core` menangani payload DUKPT dan HTTP sale; `device-sdk-api`, `device-sdk-edcsdk`, serta `device-sdk-mpos` menangani kontrak dan adapter vendor; `device-sdk-factory` menjadi satu-satunya titik pemilihan vendor; `signing-core` menandatangani request; `common-core` menyediakan kontrak jaringan.

Layar pembayaran kartu mengikuti desain `mobile-apps-cashlez/app-v3` dan memakai pembacaan kartu fisik dari SDK vendor. AID, contactless AID, CAPK production, dan tag profile diadopsi dari assets `app-v3`. Data TRACK, AMOUNT, dan ICC diproses dengan key DUKPT hasil provisioning. PIN block memakai key DUKPT hardware (`pinKsn` dari reader) kalau tersedia; reader lama tanpa dukungan itu jatuh ke enkripsi PIN block via software dengan key DUKPT purpose `PIN`, supaya vendor lain tetap kompatibel. Fake reader hanya berada di test fixtures dan tidak ikut runtime produksi.

Validasi akhir terhadap backend dan terminal fisik tetap diperlukan sebelum rilis produksi.

Verifikasi lokal: `gradlew.bat :provisioning-core:testDebugUnitTest :cdcp-core:testDebugUnitTest :device-sdk-edcsdk:testDebugUnitTest :device-sdk-mpos:testDebugUnitTest :device-sdk-factory:testDebugUnitTest :feature-card-payment:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug checkModuleBoundaries`. (`checkModuleBoundaries` sudah terhubung ke task `check`, jadi `gradlew.bat build` juga mencakupnya.)
