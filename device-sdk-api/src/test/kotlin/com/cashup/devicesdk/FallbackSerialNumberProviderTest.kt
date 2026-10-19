package com.cashup.devicesdk

import com.cashup.devicesdk.fake.FakeSerialNumberProvider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FallbackSerialNumberProviderTest {
    @Test
    fun `uses primary when it has a serial`() = runTest {
        val provider = FallbackSerialNumberProvider(
            FakeSerialNumberProvider("EDC-1"),
            FakeSerialNumberProvider("ANDROID-1"),
        )
        assertEquals("EDC-1", provider.serialNumber())
    }

    @Test
    fun `falls back when primary returns null`() = runTest {
        val provider = FallbackSerialNumberProvider(
            FakeSerialNumberProvider(null),
            FakeSerialNumberProvider("ANDROID-1"),
        )
        assertEquals("ANDROID-1", provider.serialNumber())
    }

    @Test
    fun `returns null when both are null`() = runTest {
        val provider = FallbackSerialNumberProvider(
            FakeSerialNumberProvider(null),
            FakeSerialNumberProvider(null),
        )
        assertNull(provider.serialNumber())
    }
}
