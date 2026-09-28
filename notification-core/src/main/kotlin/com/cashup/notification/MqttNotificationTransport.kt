package com.cashup.notification

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * Wrapper Eclipse Paho untuk [PaymentNotificationTransport]. Broker dev saat
 * ini: plain TCP tanpa TLS, anonymous tanpa username/password (spec §1/§4).
 *
 * `messages` pakai [MutableSharedFlow] replay=0, bukan `callbackFlow` --
 * lebih sederhana untuk mencocokkan kontrak §3 "hot stream, semua subscriber
 * melihat pesan yang sama, tidak ada replay" tanpa perlu membridging ulang
 * callback Paho per collector.
 */
class MqttNotificationTransport(
    private val host: String,
    private val port: Int,
) : PaymentNotificationTransport {

    private val internalMessages = MutableSharedFlow<RawNotification>(extraBufferCapacity = 64)
    override val messages: Flow<RawNotification> = internalMessages.asSharedFlow()

    private var client: MqttClient? = null

    override val isConnected: Boolean
        get() = client?.isConnected == true

    override fun connect(deviceId: String) {
        if (isConnected) return
        val mqttClient = MqttClient("tcp://$host:$port", "edc-$deviceId", MemoryPersistence())
        mqttClient.setCallback(object : MqttCallback {
            override fun messageArrived(topic: String, message: MqttMessage) {
                internalMessages.tryEmit(RawNotification(topic, String(message.payload, Charsets.UTF_8)))
            }

            override fun connectionLost(cause: Throwable?) = Unit

            override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
        })
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
        }
        mqttClient.connect(options)
        mqttClient.subscribe(topicFor(deviceId), 1)
        client = mqttClient
    }

    override fun disconnect() {
        client?.let { current ->
            if (current.isConnected) current.disconnect()
            current.close()
        }
        client = null
    }

    companion object {
        fun topicFor(deviceId: String): String = "cashup/staging/v1/devices/$deviceId"
    }
}
