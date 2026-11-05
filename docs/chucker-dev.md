# Inspeksi HTTP di dev (Chucker)

Hanya untuk build **debug**. Release tidak memuat library Chucker maupun kodenya.

## Cara kerja

- Klien HTTP dibuat di dalam SDK (Retrofit di atas OkHttp), bukan di app. SDK 0.1.3 menambah parameter
  `TransportPolicy(networkInterceptors = listOf(...))`. Semua klien SDK (sale, QRIS, provisioning) yang dibuat dari
  policy itu memasang interceptor tersebut sebagai *network interceptor*, setelah penandatanganan, sehingga yang
  terekam adalah request final di kabel (termasuk header `X-Timestamp`, `X-Device-Id`, `X-Correlation-Id`).
- SDK tidak bergantung pada Chucker. Ia hanya menerima daftar `Interceptor` OkHttp generik; default-nya kosong.
- App yang memakai SDK menentukan sendiri: isi daftar itu (aktif) atau biarkan kosong (nonaktif).

## Di app ini

| Berkas | Peran |
| --- | --- |
| `app/build.gradle.kts` | `debugImplementation("com.github.chuckerteam.chucker:library:4.0.0")` |
| `app/src/debug/.../di/DevNetworkInspector.kt` | Membuat `ChuckerInterceptor` |
| `app/src/release/.../di/DevNetworkInspector.kt` | Mengembalikan daftar kosong |
| `AppContainer.kt` | `TransportPolicy(..., networkInterceptors = DevNetworkInspector.interceptors(appContext))` |

Mematikan sementara tanpa menghapus kode: ubah `interceptors()` di berkas debug agar mengembalikan `emptyList()`.
Mematikan permanen: hapus `debugImplementation` dan berkas debug, lalu hapus argumen di `AppContainer`.

## Memakai di perangkat

1. Pasang APK debug dan jalankan transaksi atau provisioning.
2. Buka daftar transaksi lewat **notifikasi Chucker**, atau ikon launcher **Chucker** yang muncul di perangkat.
   Android 13+ meminta izin notifikasi; tanpa izin, pakai ikon launcher.
3. Ketuk satu entri untuk melihat tab Overview, Request, dan Response (header dan body).

## Yang terekam

Semua API: `qr-redeem`, `package`, `activate`, `POST v1/cdcp/sales`, `pay-apps`, `qr-codes`, `inquiries`.

- Body dibaca penuh sampai 250 KB per pesan (`MAX_BODY_BYTES`); yang lebih besar dipotong.
- Header yang disamarkan: `X-Signature`, `X-Nonce`, `X-Activation-Token`, `Authorization`, `Cookie`.
- Data dihapus otomatis setelah 1 jam (`RETENTION`).

## Peringatan keamanan

Chucker menyimpan body request dan response ke database di sandbox app. Untuk sale itu memuat data kartu dan
PIN block terenkripsi DUKPT beserta KSN, dan untuk `package` memuat paket kunci terbungkus. Aturan pembayaran
melarang menyimpan atau me-log data ini. Pengecualian ini diambil secara sadar untuk lingkungan dev saja:

- Pakai kartu uji dan kunci lab, bukan data produksi.
- Jangan pasang APK debug di perangkat produksi atau menyebarkannya di luar tim.
- Jangan membagikan tangkapan layar atau export Chucker (**Share**) yang memuat body sale/package.
- Hapus data setelah selesai lewat menu **Clear** di layar Chucker.

## Memeriksa bahwa release bersih

```
./gradlew :app:dependencyInsight --dependency chucker --configuration releaseRuntimeClasspath
```

Hasil yang benar: `No dependencies matching given input were found`.
