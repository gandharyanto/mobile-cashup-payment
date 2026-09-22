package com.cashup.devicesdk.edcsdk

import com.cashup.devicesdk.CardAuthorization
import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardTransactionData
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EdcSdkCardReaderTest {

    @Test
    fun `vendor error is completed once when stop emits the same error synchronously`() = runTest {
        val gateway = ReentrantStopGateway()
        val reader = EdcSdkCardReader(gateway)

        val result = reader.transact(
            CardTransactionRequest(amount = 1_000, timeoutMillis = 5_000),
            object : CardTransactionListener {
                override suspend fun authorize(data: CardTransactionData) =
                    CardAuthorization(approved = false, responseCode = "96")
            },
        )

        assertTrue(result is CardReadResult.Failure)
        assertEquals("PIN gagal", (result as CardReadResult.Failure).reason)
        assertEquals(1, gateway.stopCalls)
    }

    private class ReentrantStopGateway : EmvGateway {
        private lateinit var callback: EmvCallback
        var stopCalls: Int = 0
            private set

        override fun connect(callback: (Boolean) -> Unit) = callback(true)

        override fun start(amount: Long, callback: EmvCallback) {
            this.callback = callback
            callback.onError(code = -1, message = "PIN gagal")
        }

        override fun stop() {
            stopCalls += 1
            callback.onError(code = -1, message = "PIN gagal")
        }

        override fun selectApplet(index: Int) = Unit
    }
}
