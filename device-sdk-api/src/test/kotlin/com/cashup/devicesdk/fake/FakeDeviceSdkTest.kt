package com.cashup.devicesdk.fake

import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import com.cashup.devicesdk.CardType
import com.cashup.devicesdk.PrintResult
import com.cashup.devicesdk.ReceiptContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * Runs a suspend block synchronously without pulling in kotlinx-coroutines
 * as a test dependency. The fakes under test never actually suspend, so the
 * continuation always resumes before this function returns.
 */
private fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return outcome!!.getOrThrow()
}

class FakeDeviceSdkTest {

    @Test
    fun `FakeCardReader returns the configured result and tracks cancel`() = runSuspend {
        val reader = FakeCardReader()
        val authorization = CardAuthorization(true, "00")
        reader.setNextResult(CardReadResult.Success(authorization))

        val result = reader.transact(CardTransactionRequest(10_000, 1000), object : CardTransactionListener {
            override suspend fun authorize(card: CardTransactionData) = authorization
        })

        assertEquals(CardReadResult.Success(authorization), result)
        assertFalse(reader.cancelCalled)
        reader.cancel()
        assertTrue(reader.cancelCalled)
    }

    @Test
    fun `FakePrinter records printed receipts and returns the configured result`() {
        val printer = FakePrinter()
        printer.setNextResult(PrintResult.Failure("out of paper"))

        val receipt = ReceiptContent(listOf("line 1", "line 2"))
        val result = printer.print(receipt)

        assertEquals(PrintResult.Failure("out of paper"), result)
        assertEquals(listOf(receipt), printer.printedReceipts)
    }

    @Test
    fun `FakeScanner returns the configured result`() = runSuspend {
        val scanner = FakeScanner()
        scanner.setNextResult("qr-payload")

        val result = scanner.scanQr(1000)

        assertEquals("qr-payload", result)
    }

    @Test
    fun `FakeDeviceSdk wires default fakes together`() {
        val sdk = FakeDeviceSdk(vendorId = "acme")

        assertEquals("acme", sdk.vendorId)
        assertTrue(sdk.cardReader is FakeCardReader)
        assertTrue(sdk.printer is FakePrinter)
        assertTrue(sdk.scanner is FakeScanner)
    }
}
