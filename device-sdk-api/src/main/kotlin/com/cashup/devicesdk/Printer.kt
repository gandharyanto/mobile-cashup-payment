package com.cashup.devicesdk

data class ReceiptContent(val lines: List<String>)

sealed class PrintResult {
    data object Success : PrintResult()
    data class Failure(val reason: String) : PrintResult()
}

interface Printer {
    fun print(receipt: ReceiptContent): PrintResult
}
