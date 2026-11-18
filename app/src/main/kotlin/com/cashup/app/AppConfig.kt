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

    /**
     * Host/port broker MQTT payment-status (staging). TLS (`mqtts`) di port 41883; sertifikat
     * server ditandatangani CA privat yang dibundel di res/raw/mqtt_ca.crt.
     * Spec awal (plain TCP 1883): docs/superpowers/specs/2026-09-28-notification-core-mqtt-design.md §1.
     */
    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        // From the build (`cashup.baseUrl`): debug falls back to the development host, release to none unless the property is set (HTTPS only and pinned, N-2).
        val DEFAULT_BASE_URL: String = BuildConfig.DEFAULT_BASE_URL

        const val KEY_MQTT_HOST = "mqtt_host"
        const val KEY_MQTT_PORT = "mqtt_port"
        const val DEFAULT_MQTT_HOST = "message-broker-stagdrc.cashlez.com"
        const val DEFAULT_MQTT_PORT = 41883
    }
}
