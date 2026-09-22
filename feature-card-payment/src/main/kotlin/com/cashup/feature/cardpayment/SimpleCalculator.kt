package com.cashup.feature.cardpayment

internal data class CalculatorDisplay(
    val expression: String = "0",
    val amount: Long = 0,
    val error: String? = null,
)

/** Logic keypad app-v3 tanpa membawa dependency POS/exp4j ke feature pembayaran. */
internal class SimpleCalculator(private val maximumAmount: Long = 999_999_999L) {
    private var expression = ""

    fun press(label: String): CalculatorDisplay = when (label) {
        "C" -> clear()
        "←" -> backspace()
        "=" -> equals()
        "%" -> percentage()
        "+", "-", "x", "/" -> operator(label.single())
        else -> if (label.all(Char::isDigit)) digits(label) else display(error = "Input tidak valid")
    }

    fun current(): CalculatorDisplay = display()

    private fun clear(): CalculatorDisplay {
        expression = ""
        return display()
    }

    private fun backspace(): CalculatorDisplay {
        if (expression.isNotEmpty()) expression = expression.dropLast(1)
        return display()
    }

    private fun digits(value: String): CalculatorDisplay {
        if (expression.isEmpty() && value.all { it == '0' }) return display()
        val candidate = expression + value
        val result = evaluate(candidate)
        if (result != null && result > maximumAmount) return display(error = "Total amount exceeds the limit")
        expression = candidate
        return display()
    }

    private fun operator(value: Char): CalculatorDisplay {
        if (expression.isEmpty()) return display()
        expression = if (expression.last().isOperator()) expression.dropLast(1) + value else expression + value
        return display()
    }

    private fun percentage(): CalculatorDisplay {
        val match = Regex("""(\d+)([+\-x/])(\d+)$""").find(expression) ?: return display()
        val base = match.groupValues[1].toLongOrNull() ?: return display()
        val percentage = match.groupValues[3].toLongOrNull() ?: return display()
        expression = expression.replaceRange(match.range, "${match.groupValues[1]}${match.groupValues[2]}${base * percentage / 100}")
        return display()
    }

    private fun equals(): CalculatorDisplay {
        val result = evaluate(expression) ?: return display(error = "Invalid expression")
        if (result !in 0..maximumAmount) return display(error = "Total amount exceeds the limit")
        val evaluatedExpression = expression.formatNumbers()
        expression = result.toString()
        return CalculatorDisplay(expression = "$evaluatedExpression = ${formatAmount(result)}", amount = result)
    }

    private fun display(error: String? = null): CalculatorDisplay {
        val amount = evaluate(expression)?.takeIf {
            expression.lastOrNull()?.isDigit() == true && it in 0..maximumAmount
        } ?: 0
        return CalculatorDisplay(
            expression = expression.ifBlank { "0" }.formatNumbers(),
            amount = amount,
            error = error,
        )
    }

    private fun evaluate(source: String): Long? {
        if (source.isBlank() || !source.last().isDigit()) return null
        val numbers = source.split(Regex("""[+\-x/]""")).map { it.toLongOrNull() ?: return null }.toMutableList()
        val operators = source.filter { it.isOperator() }.toMutableList()
        var index = 0
        while (index < operators.size) {
            val operator = operators[index]
            if (operator == 'x' || operator == '/') {
                val value = if (operator == 'x') Math.multiplyExact(numbers[index], numbers[index + 1])
                else numbers[index].takeIf { numbers[index + 1] != 0L }?.div(numbers[index + 1]) ?: return null
                numbers[index] = value
                numbers.removeAt(index + 1)
                operators.removeAt(index)
            } else index++
        }
        var result = numbers.first()
        operators.forEachIndexed { operatorIndex, operator ->
            result = if (operator == '+') Math.addExact(result, numbers[operatorIndex + 1])
            else Math.subtractExact(result, numbers[operatorIndex + 1])
        }
        return result
    }

    private fun String.formatNumbers(): String = Regex("""\d+""").replace(this) {
        formatAmount(it.value.toLongOrNull() ?: return@replace it.value)
    }

    private fun formatAmount(value: Long): String = String.format(java.util.Locale("id", "ID"), "%,d", value)
    private fun Char.isOperator(): Boolean = this == '+' || this == '-' || this == 'x' || this == '/'
}
