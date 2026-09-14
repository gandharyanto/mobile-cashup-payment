package com.cashup.common.network

import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitFactory {

    private const val DEFAULT_TIMEOUT_SECONDS = 15L

    fun create(
        baseUrl: String,
        interceptors: List<Interceptor> = emptyList(),
        gson: Gson = Gson(),
        timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
    ): Retrofit {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)

        interceptors.forEach { clientBuilder.addInterceptor(it) }

        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(clientBuilder.build())
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }
}
