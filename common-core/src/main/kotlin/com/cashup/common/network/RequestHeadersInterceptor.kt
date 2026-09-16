package com.cashup.common.network

import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * Menambahkan `X-Timestamp` (ISO-8601 offset) dan `X-Correlation-Id` ke setiap
 * request. Backend mewajibkan keduanya di semua path `/v1/`, termasuk
 * `qr-redeem` yang tidak ditandatangani — karena itu interceptor ini tinggal di
 * `common-core`, bukan di `signing-core`.
 *
 * **Urutan pemasangan mengikat**: interceptor ini harus terdaftar SEBELUM
 * `SigningInterceptor`, yang membaca `X-Timestamp` yang ditulis di sini untuk
 * menyusun canonical string. Terbalik, tanda tangannya tidak akan cocok dengan
 * timestamp yang benar-benar terkirim.
 *
 * Keduanya dibuat baru per percobaan HTTP. Retry boleh punya timestamp dan
 * correlation id baru — itu bukan bagian dari sidik jari idempotency, yang
 * dibawa header `Idempotency-Key` terpisah per aksi pengguna.
 *
 * Timestamp dirakit sendiri dengan [SimpleDateFormat], **bukan**
 * `java.time.OffsetDateTime`: seluruh `java.time.*` baru ada di API 26,
 * sementara module ini `kotlin("jvm")` murni (tanpa Android Lint, jadi
 * pemeriksaan `NewApi` tidak pernah melihatnya) dan class-nya dikirim ke runtime
 * `:app` yang menyasar `minSdk` 23. Pemakaian `OffsetDateTime` di sini membuat
 * request PERTAMA yang pernah dikirim app — `qr-redeem` — mati dengan
 * `NoClassDefFoundError` di perangkat Android 6–7.1, tepat di tengah rentang
 * target proyek ini. Ini instance KETIGA dari kelas bug yang sama di branch ini,
 * setelah AppCompat di `device-sdk-edcsdk` dan `java.util.Base64` di
 * `signing-core`: module JVM murni yang kodenya berjalan di device tapi tidak
 * pernah diperiksa Android Lint.
 */
class RequestHeadersInterceptor(
    private val clock: () -> String = { isoTimestamp() },
    private val correlationIdFactory: () -> String = { "edc-" + UUID.randomUUID() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Timestamp", clock())
            .header("X-Correlation-Id", correlationIdFactory())
            .build()
        return chain.proceed(request)
    }

    companion object {

        /**
         * `yyyy-MM-dd'T'HH:mm:ss±HH:mm`, bentuk yang sama yang dulu dihasilkan
         * `OffsetDateTime.toString()`.
         *
         * Pola offset yang dipakai adalah `Z` (`+0700`) dan titik duanya
         * disisipkan manual — BUKAN pola `XXX`, yang menghasilkan `+07:00`
         * langsung tapi baru didukung `SimpleDateFormat` Android sejak API 24.
         * Memakainya di sini akan mengulang bug yang justru sedang diperbaiki,
         * hanya dua API level lebih tinggi. `Z` ada sejak API 1.
         *
         * [Locale.US] eksplisit supaya angka tetap ASCII di perangkat dengan
         * locale yang memakai digit lain.
         */
        fun isoTimestamp(
            at: Date = Date(),
            zone: TimeZone = TimeZone.getDefault(),
        ): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
            format.timeZone = zone
            val formatted = format.format(at)
            // "+0700" -> "+07:00"; lima karakter terakhir selalu offset numerik.
            return formatted.substring(0, formatted.length - 2) + ":" +
                    formatted.substring(formatted.length - 2)
        }
    }
}
