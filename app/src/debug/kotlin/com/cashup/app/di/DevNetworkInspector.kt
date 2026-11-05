package com.cashup.app.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerCollector
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.chuckerteam.chucker.api.RetentionManager
import okhttp3.Interceptor

/**
 * Debug-only HTTP inspector (Chucker). It records every request and response of the SDK clients, bodies included,
 * into a database on the device, so it exists only in the debug source set; release has an empty counterpart.
 * Signature, nonce and token headers are masked; recordings expire after [RETENTION].
 */
internal object DevNetworkInspector {

    private val RETENTION = RetentionManager.Period.ONE_HOUR
    private const val MAX_BODY_BYTES = 250_000L

    fun interceptors(context: Context): List<Interceptor> {
        val collector = ChuckerCollector(context, showNotification = true, retentionPeriod = RETENTION)
        val interceptor = ChuckerInterceptor.Builder(context)
            .collector(collector)
            .maxContentLength(MAX_BODY_BYTES)
            .redactHeaders("X-Signature", "X-Nonce", "X-Activation-Token", "Authorization", "Cookie")
            .alwaysReadResponseBody(true)
            .build()
        return listOf(interceptor)
    }
}
