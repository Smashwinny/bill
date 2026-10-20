package com.hulk.manualledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CategoryUiTest {
    @get:Rule val compose = createComposeRule()
    private fun node(id: String, name: String, parent: String? = null) = LedgerCategoryEntity(
        id, ManualTransactionType.EXPENSE, name, parent, 0, false, 1, 1)
    private val categories = listOf(node("old", "饮食"), node("new", "购物"), node("a", "早餐", "old"), node("b", "早餐", "new"))
    private val row = ManualTransactionEntity("bill", ManualTransactionType.EXPENSE, 1234, category = "饮食 › 早餐", categoryId = "a",
        account = "现金", targetAccount = null, occurredAtMs = 1700000000000, note = "唯一定位流水", createdAtMs = 1, updatedAtMs = 1)

    @Test fun changingCategoryUnderOldSearchKeepsBillVisibleAtNewPath() {
        compose.setContent {
            var rows by remember { mutableStateOf(listOf(row)) }
            MaterialTheme { Column {
                FlowPage(rows, categories, {}, {}, {}, {}, emptyList(), {}, {}, { _, _ -> }, { id, categoryId ->
                    rows = rows.map { if (it.id == id) it.copy(categoryId = categoryId, category = CategoryTree.path(categoryId, categories)) else it }
                })
            } }
        }
        compose.onNodeWithText("搜索分类、账户、备注或金额").performTextInput("饮食")
        compose.onNodeWithText("改分类").performClick()
        compose.onNodeWithText("搜索完整分类路径").performTextInput("购物")
        compose.onNodeWithTag("category-option-b").performScrollTo().performClick()
        compose.onNodeWithText("唯一定位流水").assertIsDisplayed()
        compose.onNodeWithText("购物 › 早餐").assertIsDisplayed()
        compose.onNodeWithText("已定位流水：购物 › 早餐 · ¥12.34").assertIsDisplayed()
    }

    @Test fun renamingCategoryRetainsItsIdentityAndLocatesNewPath() {
        var renamedId = ""
        compose.setContent {
            var nodes by remember { mutableStateOf(listOf(node("a", "早餐"))) }
            MaterialTheme {
                CategoryTreeManagerDialog(nodes, emptyList(), {}, { _, _ -> }, { id, name ->
                    renamedId = id
                    nodes = nodes.map { if (it.id == id) it.copy(name = name) else it }
                }, { _, _, _ -> }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("rename-a").performScrollTo().performClick()
        compose.onNodeWithText("分类名称").performTextReplacement("早饭")
        compose.onNodeWithText("保存分类").performClick()
        compose.onNodeWithText("已定位：早饭").assertIsDisplayed()
        compose.runOnIdle { assertEquals("a", renamedId) }
    }
    @Test fun movingCategoryLocatesItAtItsNewCompletePath() {
        compose.setContent {
            var nodes by remember { mutableStateOf(listOf(node("a", "早餐"), node("new", "购物"))) }
            MaterialTheme {
                CategoryTreeManagerDialog(nodes, emptyList(), {}, { id, parent ->
                    nodes = nodes.map { if (it.id == id) it.copy(parentId = parent) else it }
                }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {})
            }
        }
        compose.onNodeWithTag("move-a").performScrollTo().performClick()
        compose.onNodeWithTag("move-target-new").performScrollTo().performClick()
        compose.onNodeWithText("已定位：购物 › 早餐").assertIsDisplayed()
    }

}
