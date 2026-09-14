package com.cashup.devicesdk.fake

import com.cashup.devicesdk.Scanner

class FakeScanner(private var result: String? = null) : Scanner {
    fun setNextResult(result: String?) {
        this.result = result
    }

    override suspend fun scanQr(timeoutMillis: Long): String? = result
}
