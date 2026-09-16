package com.cashup.provisioning.data.remote

import com.cashup.common.network.RequestHeadersInterceptor
import com.cashup.provisioning.data.ProvisioningRepository
import com.cashup.signing.DeviceSigner
import com.cashup.signing.SigningInterceptor
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Membangun dua client, bukan satu.
 *
 * `qr-redeem` harus TIDAK ditandatangani — backend belum mengenal public key
 * device pada titik itu. Memisahkannya di level client lebih aman daripada
 * mengecualikan satu endpoint di dalam satu interceptor, karena pengecualian
 * berbasis path diam-diam rusak begitu path berubah.
 */
object ProvisioningHttp {

    /**
     * 65 detik, jauh di atas default 15 detik milik `RetrofitFactory`.
     * `/package` menyentuh Payment HSM dan General Purpose HSM di sisi backend,
     * dan `edc-mobile` sudah menetapkan angka ini terhadap backend yang sama.
     * Default bersama sengaja TIDAK dinaikkan — itu akan memperlambat deteksi
     * kegagalan untuk setiap pemakai lain.
     */
    const val TIMEOUT_SECONDS = 65L

    fun create(
        baseUrl: String,
        deviceSigner: DeviceSigner,
        debugLogging: Boolean = false,
    ): ProvisioningRepository {
        val gson = Gson()
        val normalized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        fun retrofit(client: OkHttpClient) = Retrofit.Builder()
            .baseUrl(normalized)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(ProvisioningApi::class.java)

        return ProvisioningRepository(
            unsigned = retrofit(clientBuilder(debugLogging).build()),
            signed = retrofit(
                clientBuilder(debugLogging)
                    // SigningInterceptor membaca X-Timestamp yang ditulis
                    // RequestHeadersInterceptor, jadi urutan ini mengikat.
                    .addInterceptor(SigningInterceptor(deviceSigner))
                    .build()
            ),
            gson = gson,
        )
    }

    private fun clientBuilder(debugLogging: Boolean) = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .addInterceptor(RequestHeadersInterceptor())
        .apply {
            // Level BODY mencetak wrappedPackageKey dan seluruh keyCheckValues
            // ke logcat. Hanya untuk build debug; rilis mentok di BASIC.
            addInterceptor(
                HttpLoggingInterceptor().apply {
                    level = if (debugLogging) {
                        HttpLoggingInterceptor.Level.BODY
                    } else {
                        HttpLoggingInterceptor.Level.BASIC
                    }
                    redactHeader("X-Signature")
                }
            )
        }
}