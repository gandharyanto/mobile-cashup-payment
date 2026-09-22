package com.cashup.devicesdk.fake

import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader
import com.cashup.devicesdk.CardTransactionListener
import com.cashup.devicesdk.CardTransactionRequest

class FakeCardReader(private var result: CardReadResult = CardReadResult.Cancelled) : CardReader {
    var cancelCalled: Boolean = false
        private set

    fun setNextResult(result: CardReadResult) {
        this.result = result
    }

    override suspend fun transact(
        request: CardTransactionRequest,
        listener: CardTransactionListener,
    ): CardReadResult = result

    override fun cancel() {
        cancelCalled = true
    }
}
