package com.hulk.manualledger

import org.junit.Assert.*
import org.junit.Test

class CategoryTreeTest {
    private fun node(id: String, name: String, parent: String? = null) = LedgerCategoryEntity(
        id, ManualTransactionType.EXPENSE, name, parent, 0, false, 1, 1)

    @Test fun fullPathsDistinguishDuplicateLeafNamesAndAnalyticsUseActualRoot() {
        val categories = listOf(node("food", "饮食"), node("shop", "购物"), node("a", "早餐", "food"), node("b", "早餐", "shop"))
        assertEquals("饮食 › 早餐", CategoryTree.path("a", categories))
        assertEquals("购物 › 早餐", CategoryTree.path("b", categories))
        val row = ManualTransactionEntity("bill", ManualTransactionType.EXPENSE, 1234,
            category = "饮食 › 早餐", categoryId = "b", account = "现金", targetAccount = null,
            occurredAtMs = 1, note = null, createdAtMs = 1, updatedAtMs = 1)
        assertEquals("购物", CategoryTree.rootName(row, categories))
        assertEquals(setOf("a", "b"), CategoryTree.selectable(row.type, categories).map { it.id }.toSet())
    }
}
