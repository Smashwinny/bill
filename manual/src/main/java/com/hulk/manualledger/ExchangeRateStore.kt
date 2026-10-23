package com.hulk.manualledger

import android.content.Context
import org.json.JSONArray
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

/** Public rates only. No transactions, amounts, account names or identifiers leave the device. */
class ExchangeRateStore(context: Context) {
    private val prefs = context.getSharedPreferences("manual_exchange_rates", Context.MODE_PRIVATE)
    fun cached(): Map<String, ExchangeQuote> = runCatching { decode(prefs.getString("rates", "[]")!!) }.getOrDefault(emptyMap())
    fun stale(now: Long = System.currentTimeMillis()): Boolean = now - prefs.getLong("fetched", 0) >= DAY_MS
    fun refresh(force: Boolean = false): Map<String, ExchangeQuote> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val fetched = prefs.getLong("fetched", 0)
        val attempted = prefs.getLong("attempted", 0)
        if (!force && !shouldFetch(now, fetched, attempted)) return@synchronized cached()
        prefs.edit().putLong("attempted", now).apply()
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            check(connection.responseCode == 200) { "汇率服务暂不可用" }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val rates = decode(body)
            check(rates.keys.containsAll(LedgerCurrencies.names.keys - "CNY")) { "汇率数据不完整" }
            check(prefs.edit().putString("rates", body).putLong("fetched", now).commit()) { "汇率缓存保存失败" }
            rates
        } finally { connection.disconnect() }
    }
    companion object {
        private val lock = Any()
        const val DAY_MS = 86_400_000L
        val ENDPOINT = "https://api.frankfurter.dev/v2/rates?base=CNY&quotes=" + (LedgerCurrencies.names.keys - "CNY").joinToString(",")
        fun shouldFetch(now: Long, fetched: Long, attempted: Long): Boolean =
            (fetched == 0L || now < fetched || now - fetched >= DAY_MS) &&
                (attempted == 0L || now < attempted || now - attempted >= 6 * 60 * 60 * 1000L)
        fun decode(body: String): Map<String, ExchangeQuote> {
            val array = JSONArray(body)
            return buildMap {
                repeat(array.length()) { index ->
                    val row = array.getJSONObject(index)
                    require(row.getString("base") == "CNY")
                    val code = row.getString("quote")
                    require(code in LedgerCurrencies.names && code != "CNY")
                    val perCny = row.getString("rate").toBigDecimal()
                    require(perCny.signum() > 0)
                    val date = LocalDate.parse(row.getString("date"))
                    require(!date.isAfter(LocalDate.now().plusDays(1)))
                    put(code, ExchangeQuote(code, BigDecimal.ONE.divide(perCny, 18, RoundingMode.HALF_UP)
                        .stripTrailingZeros().toPlainString(), date.toString()).validate())
                }
            }
        }
    }
}
