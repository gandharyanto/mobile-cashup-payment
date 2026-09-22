package com.cashup.devicesdk.factory

import com.cashup.devicesdk.Capability
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.DeviceSdk
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.Scanner
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceSdkFactoryTest {

    private class FakeDeviceSdk(override val vendorId: String) : DeviceSdk {
        override val capabilities: Set<Capability> = emptySet()
        override val cardReader: CardReader? = null
        override val printer: Printer? = null
        override val scanner: Scanner? = null
    }

    @Test
    fun `connect returns the first candidate that connects`() = runTest {
        val fake = FakeDeviceSdk("fake")
        val factory = DeviceSdkFactory(listOf({ null }, { fake }, { error("should not be reached") }))

        assertEquals(fake, factory.connect())
    }

    @Test
    fun `connect treats a candidate Exception as unavailable and tries the next one`() = runTest {
        val fake = FakeDeviceSdk("fake")
        val factory = DeviceSdkFactory(listOf({ throw IllegalStateException("vendor bind failed") }, { fake }))

        assertEquals(fake, factory.connect())
    }

    @Test
    fun `connect throws NoDeviceSdkAvailableException when nothing connects`() = runTest {
        val factory = DeviceSdkFactory(listOf({ null }, { null }))

        assertThrows(NoDeviceSdkAvailableException::class.java) {
            kotlinx.coroutines.runBlocking { factory.connect() }
        }
    }

    @Test
    fun `connect lets an Error propagate instead of treating it as unavailable`() = runTest {
        val factory = DeviceSdkFactory(listOf({ throw OutOfMemoryError("simulated build defect") }))

        assertThrows(OutOfMemoryError::class.java) {
            kotlinx.coroutines.runBlocking { factory.connect() }
        }
    }
}
