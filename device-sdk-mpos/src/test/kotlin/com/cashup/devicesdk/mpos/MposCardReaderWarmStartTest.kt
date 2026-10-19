package com.cashup.devicesdk.mpos

import com.cashup.devicesdk.PairedDeviceInfo
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MposCardReaderWarmStartTest {

    private class FakeLink(
        private val remembered: PairedDeviceInfo? = null,
        private val selectDelayMillis: Long = 0,
    ) : MposLink {
        var ready = false
        var selects = 0
        val gateway = io.mockk.mockk<MposEmvGateway>(relaxed = true)
        override suspend fun scan() = emptyList<PairedDeviceInfo>()
        override suspend fun select(id: String): MposEmvGateway? {
            selects++
            if (selectDelayMillis > 0) delay(selectDelayMillis)
            ready = true
            return gateway
        }
        override fun remembered() = remembered
        override fun isReady(id: String) = ready
    }

    @Test
    fun `rememberedDevice reports the device the link remembers`() = runTest {
        val reader = MposCardReader(FakeLink(remembered = PairedDeviceInfo("AA:BB", "ANFU-1")))

        assertEquals(PairedDeviceInfo("AA:BB", "ANFU-1"), reader.rememberedDevice())
    }

    @Test
    fun `rememberedDevice is null when nothing is remembered`() = runTest {
        assertNull(MposCardReader(FakeLink()).rememberedDevice())
    }

    @Test
    fun `selectDevice does not reconnect a reader that is already ready`() = runTest {
        val link = FakeLink()
        val reader = MposCardReader(link)

        assertTrue(reader.selectDevice("AA:BB"))
        assertTrue(reader.selectDevice("AA:BB"))

        assertEquals(1, link.selects)
    }

    @Test
    fun `selectDevice connects again once the reader is no longer ready`() = runTest {
        val link = FakeLink()
        val reader = MposCardReader(link)
        reader.selectDevice("AA:BB")
        link.ready = false

        assertTrue(reader.selectDevice("AA:BB"))

        assertEquals(2, link.selects)
    }

    @Test
    fun `concurrent selectDevice calls connect only once`() = runTest {
        val link = FakeLink(selectDelayMillis = 1_000)
        val reader = MposCardReader(link)

        val results = listOf(
            async { reader.selectDevice("AA:BB") },
            async { reader.selectDevice("AA:BB") },
        )
        advanceUntilIdle()

        assertTrue(results.awaitAll().all { it })
        assertEquals(1, link.selects)
    }

    @Test
    fun `a failed connect is reported as false`() = runTest {
        val failing = object : MposLink {
            override suspend fun scan() = emptyList<PairedDeviceInfo>()
            override suspend fun select(id: String): MposEmvGateway? = null
        }

        assertFalse(MposCardReader(failing).selectDevice("AA:BB"))
    }

    @Test
    fun `a reader that is already connected is refreshed when its session went stale`() = runTest {
        val link = FakeLink()
        val reader = MposCardReader(link)
        reader.selectDevice("AA:BB")

        assertTrue(reader.selectDevice("AA:BB"))

        // Sekali saat connect pertama tidak dihitung (select membuat gateway baru); panggilan kedua menyegarkan.
        coVerify(exactly = 1) { link.gateway.awaitReady(any()) }
    }

    @Test
    fun `a failed refresh reports the reader as not ready`() = runTest {
        val link = FakeLink()
        val reader = MposCardReader(link)
        reader.selectDevice("AA:BB")
        coEvery { link.gateway.awaitReady(any()) } throws IllegalStateException("Parameter EMV tidak termuat")

        assertFalse(reader.selectDevice("AA:BB"))
    }
}
