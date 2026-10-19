package com.cashup.devicesdk.mpos

import com.cz.anfubridge.AnfuReader
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorMposLinkTest {

    private fun candidate(brandKey: String) =
        DeviceCandidate("AA:BB", "Reader", Channel.MPOS, mapOf("brandKey" to brandKey))

    private fun link(candidate: DeviceCandidate, reader: AnfuReader) = VendorMposLink(
        context = mockk(relaxed = true),
        connectionManager = mockk<DeviceConnectionManager> { coEvery { scan(Channel.MPOS) } returns listOf(candidate) },
        anfuReader = { reader },
    )

    @Test
    fun `ANFU reader is connected through the bridge and gets the bridge EMV gateway`() = runTest {
        val reader = mockk<AnfuReader> {
            coEvery { connect("AA:BB", "Reader") } returns Unit
            every { isConnected } returns true
        }
        val link = link(candidate("anfu"), reader)
        link.scan()

        val gateway = link.select("AA:BB")

        assertTrue(gateway is AnfuEmvGateway)
        coVerify { reader.connect("AA:BB", "Reader") }
    }

    @Test
    fun `ANFU reader that does not stay connected yields no gateway`() = runTest {
        val reader = mockk<AnfuReader> {
            coEvery { connect(any(), any()) } returns Unit
            every { isConnected } returns false
        }
        val link = link(candidate("anfu"), reader)
        link.scan()

        assertNull(link.select("AA:BB"))
    }

    @Test
    fun `bridge connect failure yields no gateway instead of throwing`() = runTest {
        val reader = mockk<AnfuReader> {
            coEvery { connect(any(), any()) } throws IllegalStateException("Pairing failed or timed out")
        }
        val link = link(candidate("anfu"), reader)
        link.scan()

        assertNull(link.select("AA:BB"))
    }

    @Test
    fun `an id that was never scanned yields no gateway`() = runTest {
        val link = link(candidate("anfu"), mockk())

        assertNull(link.select("AA:BB"))
    }

    @Test
    fun `non-ANFU candidate does not touch the bridge reader`() = runTest {
        val reader = mockk<AnfuReader>()
        val link = link(candidate("newland"), reader)
        link.scan()

        // Vendor path: the mocked DeviceConnectionManager has no connect stub, so it fails -> null, bridge untouched.
        assertNull(link.select("AA:BB"))
        coVerify(exactly = 0) { reader.connect(any(), any()) }
    }
}
