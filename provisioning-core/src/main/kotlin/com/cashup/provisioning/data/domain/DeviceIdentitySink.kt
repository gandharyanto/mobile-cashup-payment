package com.cashup.provisioning.domain

/**
 * Tempat [ProvisionDeviceUseCase] mengumumkan nomor seri device **selama**
 * ceremony berlangsung, sebelum state tersimpan.
 *
 * Ada karena urutan langkah J1 menciptakan lubang: `X-Device-Id` (spec §3.3)
 * bernilai nomor seri, dan dua dari tiga request provisioning — `/package` dan
 * `/activate` — ditandatangani, jadi keduanya butuh nomor seri itu. Tapi nomor
 * seri baru **tersimpan** di langkah TERAKHIR (`state.save`). Tanpa jalur ini,
 * `StoredDeviceSigner.deviceId()` selalu null sepanjang ceremony dan setiap
 * percobaan provisioning pada device bersih gagal di `DOWNLOAD_PACKAGE` dengan
 * `DeviceNotProvisionedException` — lingkaran tertutup yang membuat J1 mustahil
 * selesai.
 *
 * Sengaja antarmuka satu-metode, bukan `StoredDeviceSigner` langsung:
 * `ProvisionDeviceUseCase` hanya bergantung pada abstraksi kecil supaya
 * tesnya tetap berjalan di JVM biasa tanpa Android Keystore.
 */
fun interface DeviceIdentitySink {

    /**
     * [serial] non-null saat nomor seri terdeteksi (langkah 1), null saat
     * rollback dan saat ceremony selesai — setelah rollback device TIDAK
     * terprovisioning, jadi identitas harus kembali kosong sampai percobaan
     * berikutnya.
     */
    fun setInFlightSerial(serial: String?)
}
