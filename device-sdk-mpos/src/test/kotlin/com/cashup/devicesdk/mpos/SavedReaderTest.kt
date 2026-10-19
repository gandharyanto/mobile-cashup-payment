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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedReaderTest {

    private class InMemoryStore(var saved: SavedReader? = null) : SavedReaderStore {
        override fun get() = saved
        override fun save(reader: SavedReader) { saved = reader }
    }

    private fun candidate(brandKey: String) =
        DeviceCandidate("AA:BB", "Reader", Channel.MPOS, mapOf("brandKey" to brandKey))

    private fun connectedReader() = mockk<AnfuReader> {
        coEvery { connect(any(), any()) } returns Unit
        every { isConnected } returns true
    }

    private fun link(
        store: SavedReaderStore,
        reader: AnfuReader,
        manager: DeviceConnectionManager = mockk { coEvery { scan(Channel.MPOS) } returns listOf(candidate("anfu")) },
    ) = VendorMposLink(
        context = mockk(relaxed = true),
        connectionManager = manager,
        anfuReader = { reader },
        savedReaders = store,
        prepareReader = { },
    )

    @Test
    fun `first successful ANFU connect saves the reader`() = runTest {
        val store = InMemoryStore()
        val link = link(store, connectedReader())
        link.scan()

        link.select("AA:BB")

        assertEquals(SavedReader("AA:BB", "Reader"), store.saved)
    }

    @Test
    fun `failed ANFU connect saves nothing`() = runTest {
        val store = InMemoryStore()
        val reader = mockk<AnfuReader> {
            coEvery { connect(any(), any()) } returns Unit
            every { isConnected } returns false
        }
        val link = link(store, reader)
        link.scan()

        assertNull(link.select("AA:BB"))
        assertNull(store.saved)
    }

    @Test
    fun `non-ANFU reader is never saved`() = runTest {
        val store = InMemoryStore()
        val manager = mockk<DeviceConnectionManager> { coEvery { scan(Channel.MPOS) } returns listOf(candidate("newland")) }
        val link = link(store, mockk(), manager)
        link.scan()

        link.select("AA:BB")

        assertNull(store.saved)
    }

    @Test
    fun `saved reader is offered without scanning`() = runTest {
        val manager = mockk<DeviceConnectionManager>()
        val link = link(InMemoryStore(SavedReader("CC:DD", "ANFU-1")), mockk(), manager)

        val devices = link.scan()

        assertEquals(1, devices.size)
        assertEquals("CC:DD", devices.single().id)
        assertEquals("ANFU-1", devices.single().name)
        coVerify(exactly = 0) { manager.scan(any()) }
    }

    @Test
    fun `saved reader is connected through the bridge without a prior scan`() = runTest {
        val reader = connectedReader()
        val link = link(InMemoryStore(SavedReader("CC:DD", "ANFU-1")), reader)
        link.scan()

        val gateway = link.select("CC:DD")

        assertTrue(gateway is AnfuEmvGateway)
        coVerify { reader.connect("CC:DD", "ANFU-1") }
    }

    @Test
    fun `saved reader that fails to connect stays saved`() = runTest {
        val store = InMemoryStore(SavedReader("CC:DD", "ANFU-1"))
        val reader = mockk<AnfuReader> {
            coEvery { connect(any(), any()) } throws IllegalStateException("Pairing failed or timed out")
        }
        val link = link(store, reader)
        link.scan()

        assertNull(link.select("CC:DD"))
        assertEquals(SavedReader("CC:DD", "ANFU-1"), store.saved)
    }

    @Test
    fun `reader is not saved when preparing it fails`() = runTest {
        val store = InMemoryStore()
        val link = VendorMposLink(
            context = mockk(relaxed = true),
            connectionManager = mockk { coEvery { scan(Channel.MPOS) } returns listOf(candidate("anfu")) },
            anfuReader = { connectedReader() },
            savedReaders = store,
            prepareReader = { throw IllegalStateException("push gagal") },
        )
        link.scan()

        assertNull(link.select("AA:BB"))
        assertNull(store.saved)
    }

    @Test
    fun `remembered reports the saved reader without scanning`() {
        val manager = mockk<DeviceConnectionManager>()
        val link = link(InMemoryStore(SavedReader("CC:DD", "ANFU-1")), mockk(), manager)

        assertEquals(com.cashup.devicesdk.PairedDeviceInfo("CC:DD", "ANFU-1"), link.remembered())
    }

    @Test
    fun `remembered is null before any reader was saved`() {
        assertNull(link(InMemoryStore(), mockk()).remembered())
    }

    @Test
    fun `a reader is ready after a successful connect and stops being ready when it disconnects`() = runTest {
        var connected = true
        val reader = mockk<AnfuReader> {
            coEvery { connect(any(), any()) } returns Unit
            every { isConnected } answers { connected }
        }
        val link = link(InMemoryStore(SavedReader("CC:DD", "ANFU-1")), reader)
        assertEquals(false, link.isReady("CC:DD"))

        link.scan()
        link.select("CC:DD")
        assertEquals(true, link.isReady("CC:DD"))
        assertEquals(false, link.isReady("OTHER"))

        connected = false
        assertEquals(false, link.isReady("CC:DD"))
    }
}
