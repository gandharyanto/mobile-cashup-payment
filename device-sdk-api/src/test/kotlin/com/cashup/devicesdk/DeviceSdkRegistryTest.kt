package com.cashup.devicesdk

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
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

    @Test
    fun `resolves to the longest matching prefix when multiple prefixes overlap`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax-a920") }
        DeviceSdkRegistry.register("PAX_A920_PRO") { FakeDeviceSdk("pax-a920-pro") }

        val resolved = DeviceSdkRegistry.resolve("PAX_A920_PRO_MAX")

        assertEquals("pax-a920-pro", resolved?.vendorId)
    }

    @Test
    fun `register throws when the same model prefix is registered twice`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        assertThrows(IllegalArgumentException::class.java) {
            DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax-again") }
        }
    }

    @Test
    fun `prefix matching is case-insensitive`() {
        DeviceSdkRegistry.register("PAX_A920") { FakeDeviceSdk("pax") }

        val resolved = DeviceSdkRegistry.resolve("pax_a920_pro")

        assertEquals("pax", resolved?.vendorId)
    }

    @Test
    fun `resolve memoizes the instance per model, invoking the factory only once`() {
        var invocationCount = 0
        DeviceSdkRegistry.register("PAX_A920") {
            invocationCount++
            FakeDeviceSdk("pax")
        }

        val first = DeviceSdkRegistry.resolve("PAX_A920_PRO")
        val second = DeviceSdkRegistry.resolve("PAX_A920_PRO")

        assertSame(first, second)
        assertEquals(1, invocationCount)
    }
}
