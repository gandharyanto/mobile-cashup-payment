package com.cashup.notification

import kotlinx.coroutines.flow.Flow

/**
 * Pesan mentah dari broker. Payload TIDAK di-parse di sini -- schema JSON dari
 * backend belum final (lihat spec §8), jadi caller (mis. [PaymentStatusWaiter]
 * lewat parameter `matches`) yang tahu cara mem-parsing & mencocokkannya.
 */
data class RawNotification(val topic: String, val payload: String)

/** Client MQTT subscribe-only, generik terhadap payload. */
interface PaymentNotificationTransport {

    val isConnected: Boolean

    /**
     * Subscribe ke `cashup/staging/v1/devices/<deviceId>`, QoS 1. Idempotent
     * kalau dipanggil ulang dengan deviceId yang sama saat sudah connected.
     */
    fun connect(deviceId: String)

    /** Hot stream -- semua subscriber melihat pesan yang sama, tidak ada replay. */
    val messages: Flow<RawNotification>

    fun disconnect()
}
