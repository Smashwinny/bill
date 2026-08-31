package com.hulk.manualledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
