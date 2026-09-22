package com.cashup.feature.cardpayment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SimpleCalculatorTest {
    @Test fun `calculator follows app-v3 keypad flow`() {
        val calculator = SimpleCalculator()
        calculator.press("1")
        calculator.press("000")
        calculator.press("+")
        calculator.press("5")
        calculator.press("000")

        val result = calculator.press("=")

        assertEquals(6_000L, result.amount)
        assertEquals("1.000+5.000 = 6.000", result.expression)
    }

    @Test fun `calculator rejects amount above nine digits`() {
        val calculator = SimpleCalculator()
        repeat(9) { calculator.press("9") }

        assertNotNull(calculator.press("9").error)
        assertEquals(999_999_999L, calculator.current().amount)
    }
}
