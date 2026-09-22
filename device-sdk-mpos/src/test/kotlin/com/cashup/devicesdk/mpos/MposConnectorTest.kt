package com.cashup.devicesdk.mpos

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.cashup.devicesdk.Capability
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MposConnectorTest {

    @Test
    fun `tryConnect returns null when there is no Bluetooth adapter`() {
        val manager = mockk<BluetoothManager> { every { adapter } returns null }
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        }

        assertNull(MposConnector.tryConnect(context))
    }

    @Test
    fun `tryConnect returns an mpos DeviceSdk when a Bluetooth adapter is present`() {
        val manager = mockk<BluetoothManager> { every { adapter } returns mockk<BluetoothAdapter>() }
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { getSystemService(Context.BLUETOOTH_SERVICE) } returns manager
        }

        val sdk = MposConnector.tryConnect(context)

        assertNotNull(sdk)
        assertEquals("mpos", sdk!!.vendorId)
        assertEquals(setOf(Capability.CARD_READ), sdk.capabilities)
        assertNull(sdk.printer)
        assertNull(sdk.scanner)
    }
}
