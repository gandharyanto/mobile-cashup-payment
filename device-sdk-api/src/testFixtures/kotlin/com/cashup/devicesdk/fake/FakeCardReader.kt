package com.cashup.devicesdk.fake

import com.cashup.devicesdk.CardReadResult
import com.cashup.devicesdk.CardReader

class FakeCardReader(private var result: CardReadResult = CardReadResult.Cancelled) : CardReader {
    var cancelCalled: Boolean = false
        private set

    fun setNextResult(result: CardReadResult) {
        this.result = result
    }

    override suspend fun waitForCard(timeoutMillis: Long): CardReadResult = result

    override fun cancel() {
        cancelCalled = true
    }
}
