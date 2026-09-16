package com.cashup.common.network

import okhttp3.Interceptor
import okhttp3.Response
import java.time.OffsetDateTime
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
 */
class RequestHeadersInterceptor(
    private val clock: () -> OffsetDateTime = OffsetDateTime::now,
    private val correlationIdFactory: () -> String = { "edc-" + UUID.randomUUID() },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header("X-Timestamp", clock().toString())
            .header("X-Correlation-Id", correlationIdFactory())
            .build()
        return chain.proceed(request)
    }
}