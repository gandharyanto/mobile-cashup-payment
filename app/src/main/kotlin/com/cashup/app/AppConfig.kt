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
     * Host/port broker MQTT payment-status. Plain TCP, tanpa TLS, port 1883 --
     * keputusan eksplisit user (bukan port 8883/TLS yang direkomendasikan
     * dokumen konektivitas broker dev), lihat
     * docs/superpowers/specs/2026-09-28-notification-core-mqtt-design.md §1.
     */
    var mqttHost: String
        get() = prefs.getString(KEY_MQTT_HOST, DEFAULT_MQTT_HOST) ?: DEFAULT_MQTT_HOST
        set(value) = prefs.edit().putString(KEY_MQTT_HOST, value).apply()

    var mqttPort: Int
        get() = prefs.getInt(KEY_MQTT_PORT, DEFAULT_MQTT_PORT)
        set(value) = prefs.edit().putInt(KEY_MQTT_PORT, value).apply()

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val DEFAULT_BASE_URL = "http://avatar.bakso.site" //domain
//        const val DEFAULT_BASE_URL = "http://192.168.90.46:8080" //local
//        const val DEFAULT_BASE_URL = "http://100.114.27.64:8080" //tailscale

        const val KEY_MQTT_HOST = "mqtt_host"
        const val KEY_MQTT_PORT = "mqtt_port"
        const val DEFAULT_MQTT_HOST = "192.168.90.46" // office LAN
//        const val DEFAULT_MQTT_HOST = "100.114.27.64" // tailscale
        const val DEFAULT_MQTT_PORT = 1883
    }
}
