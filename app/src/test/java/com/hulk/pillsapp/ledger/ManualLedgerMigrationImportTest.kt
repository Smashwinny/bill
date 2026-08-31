package com.hulk.pillsapp.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualLedgerMigrationImportTest {
    @Test
    fun parsesVersionedManualLedgerWithoutChangingAuthority() {
        val json = """
            {"schema":"manual-ledger-v1","transactions":[
              {"id":"a","type":"EXPENSE","amount_cents":1600,"currency":"CNY","category":"餐饮","account":"支付宝","target_account":null,"occurred_at_ms":1700000000000,"note":"午餐","updated_at_ms":1700000000000},
              {"id":"b","type":"INCOME","amount_cents":800000,"currency":"CNY","category":"工资","account":"银行卡","target_account":null,"occurred_at_ms":1700100000000,"note":null,"updated_at_ms":1700100000000}
            ]}
        """.trimIndent()
        val preview = ManualLedgerMigrationParser.parse(json.toByteArray())
        assertTrue(preview.canImport)
        assertEquals(2, preview.rows.size)
        assertEquals(TxType.PAYMENT, preview.rows[0].txType)
        assertEquals(TxType.INCOME, preview.rows[1].txType)
        assertEquals("支付宝", preview.rows[0].account)
    }

    @Test
    fun rejectsWrongSchemaInvalidRowsAndDuplicateIds() {
        val wrong = ManualLedgerMigrationParser.parse(
            "{\"schema\":\"other\",\"transactions\":[]}".toByteArray()
        )
        assertFalse(wrong.canImport)
        assertTrue(wrong.error!!.contains("manual-ledger-v1"))

        val duplicate = """
            {"schema":"manual-ledger-v1","transactions":[
              {"id":"same","type":"EXPENSE","amount_cents":1,"category":"A","account":"X","occurred_at_ms":1},
              {"id":"same","type":"EXPENSE","amount_cents":1,"category":"A","account":"X","occurred_at_ms":1}
            ]}
        """.trimIndent()
        val preview = ManualLedgerMigrationParser.parse(duplicate.toByteArray())
        assertFalse(preview.canImport)
        assertEquals(1, preview.rows.size)
        assertEquals(1, preview.invalidRows)
    }
}
