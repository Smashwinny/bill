package com.hulk.manualledger

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

object LedgerCurrencies {
    val names = linkedMapOf("CNY" to "人民币", "USD" to "美元", "EUR" to "欧元", "HKD" to "港元", "JPY" to "日元",
        "GBP" to "英镑", "AUD" to "澳元", "CAD" to "加元", "SGD" to "新加坡元", "CHF" to "瑞郎",
        "NZD" to "新西兰元", "KRW" to "韩元", "THB" to "泰铢", "INR" to "印度卢比", "TWD" to "新台币", "MYR" to "马来西亚令吉")
    private val aliases = names.keys.associateWith { listOf(it, names.getValue(it)) }.toMutableMap().apply {
        this["CNY"] = getValue("CNY") + listOf("RMB", "￥", "¥", "元")
        this["USD"] = getValue("USD") + listOf("US$", "$", "美金")
        this["EUR"] = getValue("EUR") + "€"
        this["GBP"] = getValue("GBP") + "£"
        this["HKD"] = getValue("HKD") + listOf("HK$", "港币")
        this["JPY"] = getValue("JPY") + listOf("日币", "円")
        this["AUD"] = getValue("AUD") + "A$"
        this["CAD"] = getValue("CAD") + "C$"
        this["SGD"] = getValue("SGD") + listOf("S$", "新元", "新币")
        this["NZD"] = getValue("NZD") + listOf("NZ$", "纽元")
        this["KRW"] = getValue("KRW") + listOf("₩", "韩币")
        this["THB"] = getValue("THB") + "฿"
        this["INR"] = getValue("INR") + "₹"
        this["TWD"] = getValue("TWD") + listOf("NT$", "台币")
        this["MYR"] = getValue("MYR") + listOf("令吉", "马币")
    }
    private val tokens = aliases.flatMap { (code, words) -> words.map { it.uppercase() to code } }.toMap()
    private val pattern = Regex(tokens.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
    data class Parsed(val expression: String, val currency: String)
    fun parse(raw: String, selected: String): Result<Parsed> = runCatching {
        require(selected in names) { "不支持的币种" }
        val matches = pattern.findAll(raw).map { it.value.uppercase() }.toList()
        val explicit = matches.filter { it !in setOf("$", "¥") }.map { tokens.getValue(it) }.toSet().singleOrNull()
        val dollars = setOf("USD", "HKD", "AUD", "CAD", "SGD", "NZD", "TWD")
        val detected = matches.map { token ->
            when (token) {
                "$" -> explicit?.takeIf { it in dollars } ?: selected.takeIf { it in dollars } ?: "USD"
                "¥" -> explicit?.takeIf { it in setOf("CNY", "JPY") } ?: if (selected == "JPY") "JPY" else "CNY"
                else -> tokens.getValue(token)
            }
        }.toSet()
        require(detected.size <= 1) { "同一笔请使用一种币种" }
        Parsed(pattern.replace(raw, ""), detected.singleOrNull() ?: selected)
    }
}

data class ExchangeQuote(val currency: String, val cnyPerUnit: String, val date: String, val source: String = "Frankfurter") {
    fun validate(): ExchangeQuote {
        require(currency in LedgerCurrencies.names && currency != "CNY") { "汇率币种无效" }
        require(cnyPerUnit.length <= 50 && cnyPerUnit.toBigDecimal().signum() > 0) { "汇率无效" }
        LocalDate.parse(date)
        require(source.isNotBlank() && source.length <= 80) { "汇率来源无效" }
        return this
    }
}

data class BookedAmount(val originalAmount: String, val originalCurrency: String, val cnyCents: Long, val quote: ExchangeQuote?)

object CurrencyAmounts {
    fun convert(amount: String, currency: String, quote: ExchangeQuote?): BookedAmount {
        require(currency in LedgerCurrencies.names) { "不支持的币种" }
        val rawCents = ManualLedgerRepository.parseCents(amount) ?: error("金额格式错误")
        val canonical = AmountExpression.format(rawCents)
        if (currency == "CNY") return BookedAmount(canonical, currency, rawCents, null)
        require(quote != null && quote.currency == currency) { "暂无可用汇率，请联网获取" }
        quote.validate()
        val cents = BigDecimal(canonical).multiply(BigDecimal(quote.cnyPerUnit))
            .setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
        require(cents > 0) { "折算金额不足人民币 0.01 元" }
        return BookedAmount(canonical, currency, cents, quote)
    }
}

fun ManualTransactionEntity.savedQuote(): ExchangeQuote? = originalCurrency?.let { currency ->
    if (exchangeRate != null && exchangeRateDate != null && exchangeRateSource != null)
        ExchangeQuote(currency, exchangeRate, exchangeRateDate, exchangeRateSource) else null
}
