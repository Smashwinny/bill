package com.hulk.manualledger

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class LedgerExperienceTest {
    @Test fun calculatorUsesPrecedenceParenthesesAndExactDecimals() {
        mapOf("3+4" to 700L, "3+4*2" to 1100L, "(3+4)×2" to 1400L,
            "0.1+0.2" to 30L, "12÷4" to 300L, "10-(-2)" to 1200L,
            " .5 + .25 " to 75L, "8−3" to 500L).forEach { (input, expected) ->
            assertEquals(input, expected, AmountExpression.evaluate(input).getOrThrow())
        }
    }
    @Test fun calculatorRoundsOnlyFinalAmountAndRejectsUnsafeOrIncompleteInput() {
        assertEquals("0.33", AmountExpression.normalized("1/3"))
        assertEquals("0.01", AmountExpression.normalized("0.005"))
        assertEquals("0.02", AmountExpression.normalized("0.005+0.01"))
        listOf("", "3+", "1/0", "2-2", "-3", "1..2", "(3+4", "3+4)", "1e3", "2(3)",
            "NaN", "9".repeat(120), "1+".repeat(61), "(".repeat(17)+"1"+")".repeat(17)).forEach {
            assertTrue(it, AmountExpression.evaluate(it).isFailure)
        }
    }
    @Test fun calculatorBoundsAtLongCentsWithoutChangingImportParser() {
        assertEquals(Long.MAX_VALUE, AmountExpression.evaluate("92233720368547758.07").getOrThrow())
        assertTrue(AmountExpression.evaluate("92233720368547758.08").isFailure)
        assertNull(ManualLedgerRepository.parseCents("3+4"))
        assertNull(ManualLedgerRepository.parseCents("1.005"))
    }
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun row(id: String, date: String, category: String = "b", created: Long = 1) = ManualTransactionEntity(
        id, ManualTransactionType.EXPENSE, 1200, category = "饮食 › 早餐", categoryId = category,
        account = "现金", targetAccount = null, occurredAtMs = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli(),
        note = null, createdAtMs = created, updatedAtMs = created)
    private fun node(id: String, parent: String? = null) = LedgerCategoryEntity(id, ManualTransactionType.EXPENSE, id, parent, 0, false, 1, 1)
    @Test fun dateFiltersIncludeExactMidnightAndLeapDayAndExcludeOtherMonthsAndTrash() {
        val rows = listOf(row("before", "2024-02-28"), row("leap", "2024-02-29"), row("after", "2024-03-01"),
            row("trash", "2024-02-29").copy(deletedAtMs = 4))
        assertEquals(listOf("leap"), LedgerBrowsing.filter(rows, YearMonth.of(2024, 3), LocalDate.of(2024, 2, 29), zone).map { it.id })
        assertEquals(listOf("leap", "before"), LedgerBrowsing.filter(rows, YearMonth.of(2024, 2), null, zone).map { it.id })
        assertEquals(3, LedgerBrowsing.filter(rows, null, null, zone).size)
        assertTrue(LedgerBrowsing.filter(rows, null, LocalDate.of(2024, 2, 27), zone).isEmpty())
    }
    @Test fun defaultUsesRememberedValidLeafThenLatestEntryNeverAlphabeticalFirst() {
        val nodes = listOf(node("礼金"), node("food"), node("b", "food"), node("c", "food"))
        val rows = listOf(row("older", "2024-01-02", "b", 1), row("newer", "2024-01-01", "c", 3))
        assertEquals("b", LedgerBrowsing.defaultCategory(ManualTransactionType.EXPENSE, nodes, rows, "b"))
        assertEquals("c", LedgerBrowsing.defaultCategory(ManualTransactionType.EXPENSE, nodes, rows, "deleted"))
        assertEquals("c", LedgerBrowsing.defaultCategory(ManualTransactionType.EXPENSE, nodes, rows, "food"))
        assertNull(LedgerBrowsing.defaultCategory(ManualTransactionType.INCOME, nodes, rows, "b"))
        assertNull(LedgerBrowsing.defaultCategory(ManualTransactionType.EXPENSE, nodes, emptyList(), null))
        assertEquals("b", LedgerBrowsing.defaultCategory(ManualTransactionType.EXPENSE, nodes, rows.map {
            if (it.id == "newer") it.copy(deletedAtMs = 10) else it }, null))
    }
}
