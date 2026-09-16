package com.cashup.app

import android.content.Context

/**
 * Base URL Front-facing API. Bukan rahasia — boleh di `SharedPreferences` biasa.
 *
 * Default menunjuk ke corepayment pengembangan di Raspberry Pi lewat Tailnet,
 * host yang sama yang dipakai `edc-mobile`. Produksi mengarah ke Front-facing
 * API lewat HTTPS.
 */
class AppConfig(context: Context) {

    private val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trimEnd('/')).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "http://100.103.104.38:8080"
    }
}
