package com.cashup.devicesdk.fake

import com.cashup.devicesdk.PrintResult
import com.cashup.devicesdk.Printer
import com.cashup.devicesdk.ReceiptContent

class FakePrinter(private var result: PrintResult = PrintResult.Success) : Printer {
    val printedReceipts = mutableListOf<ReceiptContent>()

    fun setNextResult(result: PrintResult) {
        this.result = result
    }

    override fun print(receipt: ReceiptContent): PrintResult {
        printedReceipts.add(receipt)
        return result
    }
}
