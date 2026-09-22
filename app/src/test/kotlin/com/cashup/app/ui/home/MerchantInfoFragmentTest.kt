package com.cashup.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantInfoFragmentTest {

    @Test
    fun `formats provisioned device info`() {
        val text = formatDeviceInfo(deviceId = "abc-123", serialNumber = "SN001", provisioned = true)
        assertEquals("Device ID: abc-123\nSerial: SN001\nStatus: Terprovisioning", text)
    }

    @Test
    fun `formats unknown device fields as dash`() {
        val text = formatDeviceInfo(deviceId = null, serialNumber = null, provisioned = false)
        assertEquals("Device ID: -\nSerial: -\nStatus: Belum terprovisioning", text)
    }
}
