package com.cashup.app.di

import android.content.Context
import okhttp3.Interceptor

/** Release counterpart of the debug HTTP inspector: records nothing and pulls in no inspector library. */
@Suppress("UNUSED_PARAMETER")
internal object DevNetworkInspector {
    fun interceptors(context: Context): List<Interceptor> = emptyList()
}
