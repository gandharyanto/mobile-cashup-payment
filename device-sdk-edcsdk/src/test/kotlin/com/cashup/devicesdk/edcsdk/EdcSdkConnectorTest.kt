package com.cashup.devicesdk.edcsdk

import android.content.Context
import com.cashup.devicesdk.Capability
import com.lib.core.SDKManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EdcSdkConnectorTest {

    @After
    fun tearDown() {
        unmockkObject(SDKManager)
    }

    @Test
    fun `tryConnect returns null when autoDetectDevice reports not connected`() = runTest {
        val context = mockk<Context> { every { applicationContext } returns this }
        mockkObject(SDKManager)
        val callbackSlot = slot<(Boolean) -> Unit>()
        every { SDKManager.autoDetectDevice(any(), capture(callbackSlot)) } answers { callbackSlot.captured(false) }

        assertNull(EdcSdkConnector.tryConnect(context))
    }

    @Test
    fun `tryConnect returns an edcsdk DeviceSdk when autoDetectDevice reports connected`() = runTest {
        val context = mockk<Context> { every { applicationContext } returns this }
        mockkObject(SDKManager)
        val callbackSlot = slot<(Boolean) -> Unit>()
        every { SDKManager.autoDetectDevice(any(), capture(callbackSlot)) } answers { callbackSlot.captured(true) }

        val sdk = EdcSdkConnector.tryConnect(context)

        assertNotNull(sdk)
        assertEquals("edcsdk", sdk!!.vendorId)
        assertEquals(setOf(Capability.CARD_READ, Capability.SCAN_QR), sdk.capabilities)
    }
}
