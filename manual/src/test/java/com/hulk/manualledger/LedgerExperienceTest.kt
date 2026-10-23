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

    @Test fun frequencyRanksByCountNotMoneyAndExcludesTrashIncomeTransfersAndOtherMonths() {
        val month = YearMonth.of(2024, 2)
        val small = (1..3).map { row("small-$it", "2024-02-29").copy(category = "饮食", amountCents = 100) }
        val large = row("large", "2024-02-01").copy(category = "购物", amountCents = 900000)
        val ignored = listOf(large.copy(id = "old", occurredAtMs = row("", "2024-01-31").occurredAtMs),
            large.copy(id = "trash", deletedAtMs = 9), large.copy(id = "income", type = ManualTransactionType.INCOME),
            large.copy(id = "transfer", type = ManualTransactionType.TRANSFER))
        val result = LedgerBrowsing.expenseFrequency(small + large + ignored, month, { it.category }, zone)
        assertEquals(listOf("饮食", "购物"), result.map { it.name })
        assertEquals(listOf(3, 1), result.map { it.count })
        assertEquals(listOf(300L, 900000L), result.map { it.amountCents })
        assertTrue(LedgerBrowsing.expenseFrequency(small, month.minusMonths(2), { it.category }, zone).isEmpty())
    }

    @Test fun frequencyTiesAreStableAndUseResolvedCurrentCategoryNames() {
        val rows = listOf(row("a", "2024-02-01", "b").copy(amountCents = 100),
            row("z", "2024-02-01", "c").copy(amountCents = 900000))
        val resolve: (ManualTransactionEntity) -> String = { if (it.categoryId == "b") "A 新分类" else "Z 新分类" }
        val expected = LedgerBrowsing.expenseFrequency(rows, YearMonth.of(2024, 2), resolve, zone)
        assertEquals(listOf("A 新分类", "Z 新分类"), expected.map { it.name })
        assertEquals(expected, LedgerBrowsing.expenseFrequency(rows.reversed(), YearMonth.of(2024, 2), resolve, zone))
        assertEquals(listOf(2), LedgerBrowsing.expenseFrequency(rows, YearMonth.of(2024, 2), { "合并后分类" }, zone).map { it.count })
    }
}
