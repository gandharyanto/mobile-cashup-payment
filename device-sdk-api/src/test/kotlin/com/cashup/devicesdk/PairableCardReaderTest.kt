package com.cashup.devicesdk

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class PairableCardReaderTest {
    private class FakePairableReader : PairableCardReader {
        var selected: String? = null
        override suspend fun pairedDevices(): List<PairedDeviceInfo> =
            listOf(PairedDeviceInfo("AA:BB", "Newland N910"))
        override suspend fun selectDevice(id: String): Boolean {
            selected = id
            return id == "AA:BB"
        }
    }

    @Test
    fun `pairedDevices lists discovered readers`() = runTest {
        val reader = FakePairableReader()
        val devices = reader.pairedDevices()
        assertEquals(1, devices.size)
        assertEquals(PairedDeviceInfo("AA:BB", "Newland N910"), devices.first())
    }

    @Test
    fun `selectDevice reports success only for a known id`() = runTest {
        val reader = FakePairableReader()
        assertTrue(reader.selectDevice("AA:BB"))
        assertFalse(reader.selectDevice("unknown"))
        assertEquals("unknown", reader.selected)
    }
}
