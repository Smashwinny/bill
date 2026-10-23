package com.hulk.manualledger

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** A bounded decimal calculator. No evaluation of code or floating-point money. */
object AmountExpression {
    fun evaluate(raw: String): Result<Long> = runCatching {
        require(raw.length <= 120) { "算式过长" }
        val source = raw.replace('×', '*').replace('÷', '/').replace('−', '-').filterNot { it.isWhitespace() }
        require(source.isNotEmpty()) { "请输入金额或算式" }
        val parser = Parser(source)
        val amount = parser.expression()
        require(parser.position == source.length) { "算式不完整或包含不支持的字符" }
        val cents = amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        require(cents > 0) { "记账金额必须大于 0" }
        cents
    }

    fun normalized(raw: String): String? = evaluate(raw).getOrNull()?.let(::format)
    fun format(cents: Long): String = BigDecimal.valueOf(cents, 2).toPlainString()

    private class Parser(private val text: String) {
        var position = 0
        private var depth = 0
        private fun take(c: Char): Boolean = (text.getOrNull(position) == c).also { if (it) position++ }
        fun expression(): BigDecimal {
            var value = term()
            while (true) value = when {
                take('+') -> value.add(term())
                take('-') -> value.subtract(term())
                else -> return value
            }
        }
        private fun term(): BigDecimal {
            var value = factor()
            while (true) value = when {
                take('*') -> value.multiply(factor(), MathContext.DECIMAL128)
                take('/') -> {
                    val divisor = factor()
                    require(divisor.signum() != 0) { "不能除以 0" }
                    value.divide(divisor, MathContext.DECIMAL128)
                }
                else -> return value
            }
        }
        private fun factor(): BigDecimal {
            require(++depth <= 16) { "括号或符号嵌套过多" }
            val result = when {
                take('+') -> factor()
                take('-') -> factor().negate()
                take('(') -> expression().also { require(take(')')) { "请补全右括号" } }
                else -> {
                    val start = position
                    while (text.getOrNull(position)?.let { it in '0'..'9' || it == '.' } == true) position++
                    require(position > start) { "算式不完整，请补全金额" }
                    text.substring(start, position).toBigDecimalOrNull()
                        ?: error("数字格式错误")
                }
            }
            depth--
            return result
        }
    }
}
