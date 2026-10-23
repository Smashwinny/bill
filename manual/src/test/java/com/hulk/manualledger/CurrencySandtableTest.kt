package com.hulk.manualledger

import org.junit.Assert.*
import org.junit.Test
import java.time.YearMonth

class CurrencySandtableTest {
    @Test fun detectsCurrencyAndRejectsMixedExpressions() {
        listOf("10美元", "$10", "USD 10", "10美金").forEach { assertEquals("USD",LedgerCurrencies.parse(it,"CNY").getOrThrow().currency) }
        assertEquals("JPY",LedgerCurrencies.parse("¥100日元","CNY").getOrThrow().currency)
        assertEquals("CAD",LedgerCurrencies.parse("$10","CAD").getOrThrow().currency)
        assertEquals("HKD",LedgerCurrencies.parse("HK$10+5","CNY").getOrThrow().currency)
        assertEquals("10+5",LedgerCurrencies.parse("HK$10+5","CNY").getOrThrow().expression)
        assertEquals("EUR",LedgerCurrencies.parse("3+4","EUR").getOrThrow().currency)
        assertTrue(LedgerCurrencies.parse("USD10+5人民币","CNY").isFailure)
        assertTrue(LedgerCurrencies.parse("10","BAD").isFailure)
    }
    @Test fun conversionUsesDecimalSnapshotRoundingAndRequiresQuote() {
        val quote=ExchangeQuote("USD","7.12345","2026-10-22")
        assertEquals(7123L,CurrencyAmounts.convert("10","USD",quote).cnyCents)
        assertEquals("10.00",CurrencyAmounts.convert("10","USD",quote).originalAmount)
        assertEquals(1000L,CurrencyAmounts.convert("10","CNY",quote).cnyCents)
        assertThrows(IllegalArgumentException::class.java) { CurrencyAmounts.convert("10","EUR",quote) }
        assertThrows(IllegalArgumentException::class.java) { CurrencyAmounts.convert("10","USD",null) }
        assertThrows(ArithmeticException::class.java) { CurrencyAmounts.convert("92233720368547758.07","USD",quote) }
    }
    @Test fun fetchPolicyIsDailyWithFailureBackoff() {
        val now=2_000_000_000_000L
        assertFalse(ExchangeRateStore.shouldFetch(now,now-1000,now-1000))
        assertTrue(ExchangeRateStore.shouldFetch(now,now-ExchangeRateStore.DAY_MS,now-ExchangeRateStore.DAY_MS))
        assertFalse(ExchangeRateStore.shouldFetch(now,0,now-1000))
        assertTrue(ExchangeRateStore.shouldFetch(now,0,0))
        assertTrue(ExchangeRateStore.shouldFetch(now,now+1000,now+1000))
    }
    @Test fun wholeBoardProjectionRotatesGridAndColumnsTogether() {
        val month=YearMonth.of(2026,2)
        assertEquals(3f,CalendarProjection.dayCenter(month,1).x)
        val floor=CalendarProjection.dayCenter(month,20)
        val p=CalendarProjection(700f,500f,0f,52f,1f)
        val turned=CalendarProjection(700f,500f,90f,52f,1f)
        assertNotEquals(p.project(floor).x,turned.project(floor).x)
        assertTrue(p.project(floor.copy(z=2f)).y < p.project(floor).y)
        val polygon=listOf(ScenePoint(-1f,-1f),ScenePoint(1f,-1f),ScenePoint(1f,1f),ScenePoint(-1f,1f)).map(turned::project)
        val center=turned.project(ScenePoint(0f,0f))
        assertTrue(CalendarProjection.contains(polygon,center.x,center.y))
        assertFalse(CalendarProjection.contains(polygon,0f,0f))
    }
}
