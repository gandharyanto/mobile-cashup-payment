package com.cashup.devicesdk.mpos

import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairedDeviceInfo
import com.lib.device.core.channel.Channel
import com.lib.device.core.manager.DeviceConnectionManager
import com.lib.device.core.model.DeviceCandidate
import com.lib.device.core.session.DeviceSession
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MposCardReaderTest {

    @Test
    fun `pairedDevices maps scan results to PairedDeviceInfo`() = runTest {
        val candidate = DeviceCandidate(id = "AA:BB", name = "Newland N910", channel = Channel.MPOS)
        val connectionManager = mockk<DeviceConnectionManager> {
            coEvery { scan(Channel.MPOS, any()) } returns listOf(candidate)
        }
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)

        val devices = reader.pairedDevices()

        assertEquals(listOf(PairedDeviceInfo("AA:BB", "Newland N910")), devices)
    }

    @Test
    fun `selectDevice returns false for an id that was never scanned`() = runTest {
        val connectionManager = mockk<DeviceConnectionManager>()
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)

        assertFalse(reader.selectDevice("never-scanned"))
    }

    @Test
    fun `selectDevice connects and returns the session's isAlive value`() = runTest {
        val candidate = DeviceCandidate(id = "AA:BB", name = "Newland N910", channel = Channel.MPOS)
        val session = mockk<DeviceSession> { every { isAlive } returns true; every { emv } returns null }
        val connectionManager = mockk<DeviceConnectionManager> {
            coEvery { scan(Channel.MPOS, any()) } returns listOf(candidate)
            coEvery { connect(Channel.MPOS, candidate) } returns session
        }
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = connectionManager)
        reader.pairedDevices()

        assertTrue(reader.selectDevice("AA:BB"))
    }

    @Test
    fun `transact fails fast when no device has been selected yet`() = runTest {
        val reader = MposCardReader(context = mockk(relaxed = true), connectionManager = mockk())

        val result = reader.transact(
            CardTransactionRequest(10_000, 1_000),
            object : CardTransactionListener {
                override suspend fun authorize(card: CardTransactionData) = CardAuthorization(true, "00")
            },
        )

        assertEquals(CardReadResult.Failure("Belum ada reader mPOS terpilih"), result)
    }
}
