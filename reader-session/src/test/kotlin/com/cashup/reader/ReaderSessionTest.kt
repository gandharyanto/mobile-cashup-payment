package com.cashup.reader

import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.PairableCardReader
import com.cashup.devicesdk.PairedDeviceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ReaderSessionTest {

    /** Terminal EDC bawaan (T6D): bukan [PairableCardReader]. */
    private class BuiltInReader : CardReader {
        override suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener) =
            CardReadResult.Cancelled
        override fun cancel() = Unit
    }

    /** Reader mPOS (ANFU). */
    private class PairableReader(
        private val remembered: PairedDeviceInfo?,
        private val selectResult: Boolean = true,
        private val selectFailure: Exception? = null,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : CardReader, PairableCardReader {
        val selected = mutableListOf<String>()
        override suspend fun transact(request: CardTransactionRequest, listener: CardTransactionListener) =
            CardReadResult.Cancelled
        override fun cancel() = Unit
        override suspend fun pairedDevices() = emptyList<PairedDeviceInfo>()
        override suspend fun selectDevice(id: String): Boolean {
            selected += id
            gate?.await()
            selectFailure?.let { throw it }
            return selectResult
        }
        override suspend fun rememberedDevice() = remembered
    }

    private val anfu = PairedDeviceInfo("AA:BB", "ANFU-1")

    private fun TestScope.session(connect: suspend () -> CardReader) = ReaderSession(connect, this)

    @Test
    fun `a built-in reader needs no preparation`() = runTest {
        val session = session { BuiltInReader() }

        assertNull(session.rememberedReader())
        assertTrue(session.prepareNow())
    }

    @Test
    fun `nothing remembered means nothing to prepare`() = runTest {
        val reader = PairableReader(remembered = null)
        val session = session { reader }

        assertNull(session.rememberedReader())
        assertTrue(session.prepareNow())
        assertTrue(reader.selected.isEmpty())
    }

    @Test
    fun `a remembered reader is connected and prepared`() = runTest {
        val reader = PairableReader(remembered = anfu)
        val session = session { reader }

        assertEquals(anfu, session.rememberedReader())
        assertTrue(session.prepareNow())
        assertEquals(listOf("AA:BB"), reader.selected)
    }

    @Test
    fun `a remembered reader that cannot be connected reports failure`() = runTest {
        val reader = PairableReader(remembered = anfu, selectResult = false)

        assertFalse(session { reader }.prepareNow())
    }

    @Test
    fun `an exception while connecting reports failure instead of crashing the caller`() = runTest {
        val reader = PairableReader(remembered = anfu, selectFailure = IllegalStateException("bluetooth mati"))

        assertFalse(session { reader }.prepareNow())
    }

    @Test
    fun `no device sdk at all reports nothing to prepare`() = runTest {
        val session = session { throw IllegalStateException("tidak ada SDK") }

        assertNull(session.rememberedReader())
        assertTrue(session.prepareNow())
    }

    @Test
    fun `cancellation is not swallowed`() = runTest {
        val session = session { throw CancellationException("dibatalkan") }

        try {
            session.prepareNow()
            fail("CancellationException harus diteruskan")
        } catch (expected: CancellationException) {
            // ok
        }
    }

    @Test
    fun `a built-in reader is probed only once however often preparation is requested`() = runTest {
        var probes = 0
        val session = session { probes++; BuiltInReader() }

        repeat(3) { session.prepareNow() }
        session.rememberedReader()

        assertEquals(1, probes)
    }

    @Test
    fun `a failed probe is retried on the next request`() = runTest {
        var probes = 0
        val session = session { probes++; throw IllegalStateException("belum siap") }

        repeat(3) { session.prepareNow() }

        assertEquals(3, probes)
    }

    @Test
    fun `an external reader is reused between transactions`() = runTest {
        var connects = 0
        val reader = PairableReader(remembered = anfu)
        val session = session { connects++; reader }

        assertSame(reader, session.cardReader())
        assertSame(reader, session.cardReader())

        assertEquals(1, connects)
    }

    @Test
    fun `a built-in reader is created fresh for every transaction`() = runTest {
        var connects = 0
        val session = session { connects++; BuiltInReader() }

        val first = session.cardReader()
        val second = session.cardReader()

        assertNotSame(first, second)
        assertEquals(2, connects)
    }

    @Test
    fun `background preparation returns the same job while it is still running`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val reader = PairableReader(remembered = anfu, gate = gate)
        val session = session { reader }

        val first = session.prepareInBackground()
        val second = session.prepareInBackground()
        testScheduler.runCurrent()

        assertSame(first, second)
        gate.complete(Unit)
        assertTrue(first.await())
        assertEquals(listOf("AA:BB"), reader.selected)
    }

    @Test
    fun `background preparation starts again once the previous one finished`() = runTest {
        val reader = PairableReader(remembered = anfu)
        val session = session { reader }

        val first = session.prepareInBackground()
        assertTrue(first.await())
        val second = session.prepareInBackground()

        assertNotSame(first, second)
        assertTrue(second.await())
    }
}
