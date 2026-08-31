package com.hulk.manualledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ManualLedgerContractTest {
    @Test
    fun amountParsingIsExact() {
        assertEquals(1234L, ManualLedgerRepository.parseCents("12.34"))
        assertEquals(null, ManualLedgerRepository.parseCents("12.345"))
        assertEquals(null, ManualLedgerRepository.parseCents("0"))
    }

    @Test
    fun suishouCsvHandlesQuotedCommaAndIsDeterministic() {
        val csv = "日期,类型,金额,分类,账户,备注\n" +
            "2026-08-31 12:30:00,支出,16.00,餐饮,支付宝,\"午餐,两人\""
        val first = SuishouCsvParser.parse(csv)
        val second = SuishouCsvParser.parse(csv)
        assertEquals(1, first.rows.size)
        assertEquals("午餐,两人", first.rows.single().note)
        assertEquals(first.rows.single().stableId, second.rows.single().stableId)
    }

    @Test
    fun suishouCsvAcceptsBomCurrencyAndThousandsSeparator() {
        val csv = "\uFEFF时间,收支类型,交易金额,项目,资金账户\n" +
            "2026/8/31 9:08,收入,\"￥1,234.50\",工资,银行卡"
        val result = SuishouCsvParser.parse(csv)
        assertEquals(0, result.rejectedRows)
        assertEquals("1234.50", result.rows.single().amountText)
        assertEquals(ManualTransactionType.INCOME, result.rows.single().type)
        assertEquals("工资", result.rows.single().category)
    }

    @Test
    fun migrationEnvelopeCarriesStableSchemaAndNoNetworkFields() {
        val row = ManualTransactionEntity(
            id = "stable-id",
            type = ManualTransactionType.EXPENSE,
            amountCents = 1600,
            category = "餐饮",
            account = "现金",
            targetAccount = null,
            occurredAtMs = 1,
            note = "测试",
            createdAtMs = 1,
            updatedAtMs = 1,
        )
        val exported = ManualLedgerMigrationCodec.exportJson(listOf(row))
        assertTrue(exported.contains("\"schema\":\"manual-ledger-v1\""))
        assertTrue(exported.contains("\"id\":\"stable-id\""))
        assertFalse(exported.contains("endpoint"))
        assertFalse(exported.contains("token"))
    }

    @Test
    fun insightsProjectMonthAndCompareWithoutFloatingPointDrift() {
        assertEquals(31000L, LedgerInsights.projectedExpense(10000, 10, 31))
        assertEquals(25, LedgerInsights.monthChangePercent(12500, 10000))
        assertEquals(-25, LedgerInsights.monthChangePercent(7500, 10000))
        assertEquals(null, LedgerInsights.monthChangePercent(7500, 0))
    }

    @Test
    fun changingTransactionDatePreservesLocalTimeOfDay() {
        val zone = ZoneId.systemDefault()
        val original = LocalDateTime.of(2026, 8, 31, 7, 46, 12).atZone(zone).toInstant().toEpochMilli()
        val changed = changeLocalDate(original, 2026, 7, 15)
        val local = java.time.Instant.ofEpochMilli(changed).atZone(zone)
        assertEquals(2026, local.year)
        assertEquals(7, local.monthValue)
        assertEquals(15, local.dayOfMonth)
        assertEquals(7, local.hour)
        assertEquals(46, local.minute)
        assertEquals(12, local.second)
    }
}
