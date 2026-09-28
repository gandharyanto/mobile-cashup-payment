package com.cashup.notification

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MqttNotificationTransportTest {
    @Test
    fun `topicFor builds the unified per-device topic`() {
        assertEquals(
            "cashup/staging/v1/devices/EDC12345",
            MqttNotificationTransport.topicFor("EDC12345"),
        )
    }
}
