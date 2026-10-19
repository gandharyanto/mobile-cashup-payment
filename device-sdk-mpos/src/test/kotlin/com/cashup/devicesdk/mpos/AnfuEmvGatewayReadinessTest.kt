package com.cashup.devicesdk.mpos

import androidx.core.content.ContextCompat
import com.cz.anfubridge.AnfuEmv
import com.cz.anfubridge.AnfuKeySession
import com.cz.anfubridge.AnfuParams
import com.cz.anfubridge.AnfuReader
import com.cz.anfubridge.AnfuTransactionListener
import com.lib.core.emv.CvmEnum
import io.mockk.slot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AnfuEmvGatewayReadinessTest {

    private val sessionKeys = mockk<AnfuKeySession>(relaxed = true)
    private val readerParams = mockk<AnfuParams>()
    private val readerEmv = mockk<AnfuEmv>(relaxed = true)
    private val reader = mockk<AnfuReader> {
        every { keySession } returns sessionKeys
        every { params } returns readerParams
        every { emv } returns readerEmv
    }
    private var nextKeyByte = 0x10
    private val gateway = AnfuEmvGateway(
        mockk(relaxed = true),
        reader,
        newKey = { ByteArray(16) { nextKeyByte.toByte() }.also { nextKeyByte += 0x10 } },
    ) { listOf(mapOf("k" to "v")) }

    init {
        every { readerParams.cachedAidCount } returns 8
        every { readerParams.cachedCapkCount } returns 57
        every { sessionKeys.writeTransportKey(any(), any()) } returns true
        every { sessionKeys.writeKek(any<ByteArray>()) } returns true
        every { sessionKeys.writePik(any()) } returns true
    }

    @Test
    fun `prepare marks the injection around loading and pushing params`() = runTest {
        coEvery { readerParams.pushToReader() } returns true

        gateway.prepare()

        verifyOrder {
            sessionKeys.markInjectionStarted()
            readerEmv.initParams(any(), any(), any(), any())
            sessionKeys.markInjectionFinished()
        }
        coVerify { readerParams.pushToReader() }
        verify(exactly = 0) { sessionKeys.markInjectionFailed() }
    }

    @Test
    fun `prepare loads the params only once`() = runTest {
        coEvery { readerParams.pushToReader() } returns true

        gateway.prepare()
        gateway.prepare()

        verify(exactly = 1) { readerEmv.initParams(any(), any(), any(), any()) }
    }

    @Test
    fun `prepare that the reader rejects marks the injection failed`() = runTest {
        coEvery { readerParams.pushToReader() } returns false

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.prepare() } }

        verify { sessionKeys.markInjectionFailed() }
        verify(exactly = 0) { sessionKeys.markInjectionFinished() }
    }

    @Test
    fun `awaitReady does nothing when the reader is ready`() = runTest {
        every { sessionKeys.isInjectionInProgress } returns false
        every { sessionKeys.needsPreparation() } returns false
        var preparing = 0

        gateway.awaitReady { preparing++ }

        assertEquals(0, preparing)
        coVerify(exactly = 0) { readerParams.pushToReader() }
    }

    @Test
    fun `awaitReady waits for an injection that is in progress`() = runTest {
        every { sessionKeys.isInjectionInProgress } returnsMany listOf(true, true, true, false, false)
        every { sessionKeys.needsPreparation() } returns false
        var preparing = 0

        gateway.awaitReady { preparing++ }

        assertEquals(1, preparing)
    }

    @Test
    fun `awaitReady fails when the injection never finishes`() = runTest {
        every { sessionKeys.isInjectionInProgress } returns true

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.awaitReady() } }
    }

    @Test
    fun `awaitReady prepares the reader when its key session expired`() = runTest {
        every { sessionKeys.isInjectionInProgress } returns false
        every { sessionKeys.needsPreparation() } returns true
        coEvery { readerParams.pushToReader() } returns true
        var preparing = 0

        gateway.awaitReady { preparing++ }

        assertEquals(1, preparing)
        coVerify { readerParams.pushToReader() }
    }

    private val zeroKey = "0".repeat(32)

    @Test
    fun `prepare installs transport key then KEK then PIK before the params`() = runTest {
        coEvery { readerParams.pushToReader() } returns true
        val kek = mutableListOf<ByteArray>()
        val pik = mutableListOf<ByteArray>()
        every { sessionKeys.writeKek(any<ByteArray>()) } answers { kek += firstArg<ByteArray>().copyOf(); true }
        every { sessionKeys.writePik(any()) } answers { pik += firstArg<ByteArray>().copyOf(); true }

        gateway.prepare()

        verifyOrder {
            sessionKeys.markInjectionStarted()
            sessionKeys.writeTransportKey(zeroKey, zeroKey)
            sessionKeys.writeKek(any<ByteArray>())
            sessionKeys.writePik(any())
            readerEmv.initParams(any(), any(), any(), any())
            sessionKeys.markInjectionFinished()
        }
        assertEquals(1, kek.size)
        assertEquals(1, pik.size)
        assertEquals(16, kek.single().size)
        assertEquals(16, pik.single().size)
        assertEquals(false, kek.single().contentEquals(pik.single()))
    }

    @Test
    fun `prepare wipes the KEK and PIK it generated`() = runTest {
        coEvery { readerParams.pushToReader() } returns true
        val generated = mutableListOf<ByteArray>()
        val wiping = AnfuEmvGateway(
            mockk(relaxed = true),
            reader,
            newKey = { ByteArray(16) { 0x7F }.also { generated += it } },
        ) { listOf(mapOf("k" to "v")) }

        wiping.prepare()

        assertEquals(2, generated.size)
        generated.forEach { assertEquals(true, it.all { b -> b == 0.toByte() }) }
    }

    @Test
    fun `prepare stops when the reader rejects the transport key`() = runTest {
        every { sessionKeys.writeTransportKey(any(), any()) } returns false

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.prepare() } }

        verify(exactly = 0) { sessionKeys.writeKek(any<ByteArray>()) }
        verify(exactly = 0) { sessionKeys.writePik(any()) }
        verify { sessionKeys.markInjectionFailed() }
        verify(exactly = 0) { sessionKeys.markInjectionFinished() }
    }

    @Test
    fun `prepare does not send the PIK when the reader rejects the KEK`() = runTest {
        every { sessionKeys.writeKek(any<ByteArray>()) } returns false

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.prepare() } }

        verify(exactly = 0) { sessionKeys.writePik(any()) }
        verify { sessionKeys.markInjectionFailed() }
    }

    @Test
    fun `prepare fails when the reader rejects the PIK`() = runTest {
        every { sessionKeys.writePik(any()) } returns false

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.prepare() } }

        verify { sessionKeys.markInjectionFailed() }
        verify(exactly = 0) { readerEmv.initParams(any(), any(), any(), any()) }
    }

    @Test
    fun `prepare fails when the AID and CAPK cache stays empty`() = runTest {
        every { readerParams.cachedAidCount } returns 0
        every { readerParams.cachedCapkCount } returns 0

        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { gateway.prepare() } }

        coVerify(exactly = 0) { readerParams.pushToReader() }
        verify { sessionKeys.markInjectionFailed() }
        verify(exactly = 0) { sessionKeys.markInjectionFinished() }
    }

    @Test
    fun `a vendor failure while starting PIN input is reported instead of thrown`() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.registerReceiver(any(), any(), any(), any()) } returns null
        val listener = slot<AnfuTransactionListener>()
        every { readerEmv.startReadCard(any(), capture(listener)) } returns Unit
        every { readerEmv.startPinInput(any(), any()) } throws UninitializedPropertyAccessException("led")
        val callback = mockk<EmvCallback>(relaxed = true)
        val pinCvm = CvmEnum.values().first {
            it != CvmEnum.EMV_CVMFLAG_NO_CVM && it != CvmEnum.EMV_CVMFLAG_SIGNATURE
        }

        gateway.start(10_000L, callback)
        listener.captured.onPinEntry(pinCvm.ordinal)

        try {
            verify { callback.onError(any(), any()) }
        } finally {
            unmockkStatic(ContextCompat::class)
        }
    }
}
