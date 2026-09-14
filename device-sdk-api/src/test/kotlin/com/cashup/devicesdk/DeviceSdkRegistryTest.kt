package com.cashup.devicesdk

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

private class FakeDeviceSdk(override val vendorId: String) : DeviceSdk {
    override val capabilities: Set<Capability> = setOf(Capability.CARD_READ)
    override val cardReader: CardReader? = null
    override val printer: Printer? = null
    override val scanner: Scanner? = null
}

class DeviceSdkRegistryTest {

    @AfterEach
    fun tearDown() {
        DeviceSdkRegistry.clearForTest()
    }

    @Test
    fun `resolves the sdk whose model prefix matches`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        val resolved = DeviceSdkRegistry.resolve("PAX_A920_PRO")

        assertEquals("pax", resolved?.vendorId)
    }

    @Test
    fun `returns null when no prefix matches`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        val resolved = DeviceSdkRegistry.resolve("SUNMI_P2")

        assertNull(resolved)
    }
}
