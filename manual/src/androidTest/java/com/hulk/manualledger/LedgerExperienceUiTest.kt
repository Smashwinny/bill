package com.hulk.manualledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class LedgerExperienceUiTest {
    @get:Rule val compose = createComposeRule()
    private fun node(id: String, name: String, parent: String? = null) = LedgerCategoryEntity(
        id, ManualTransactionType.EXPENSE, name, parent, 0, false, 1, 1)
    private val categories = listOf(node("gift", "礼金"), node("food", "饮食"), node("out", "外食", "food"),
        node("breakfast", "早餐", "out"), node("shop", "购物"), node("other-breakfast", "早餐", "shop"))
    private fun row(id: String, day: Int, cents: Long = 1200) = ManualTransactionEntity(id, ManualTransactionType.EXPENSE,
        cents, category = "饮食 › 外食 › 早餐", categoryId = "breakfast", account = "现金", targetAccount = null,
        occurredAtMs = LocalDate.of(2026, 9, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        note = "流水-$id", createdAtMs = 1, updatedAtMs = 1)

    @Test fun categoryPickerNavigatesOneLevelAtATimeAndSearchDisambiguates() {
        var chosen = ""
        compose.setContent { MaterialTheme { LeafCategoryDialog(ManualTransactionType.EXPENSE, categories, null, {}, { chosen = it }) } }
        compose.onNodeWithTag("category-option-breakfast").assertDoesNotExist()
        compose.onNodeWithTag("category-option-food").performClick()
        compose.onNodeWithText("第 2 级分类").assertIsDisplayed()
        compose.onNodeWithTag("category-option-gift").assertDoesNotExist()
        compose.onNodeWithTag("category-option-breakfast").assertDoesNotExist()
        compose.onNodeWithTag("category-option-out").performClick()
        compose.onNodeWithText("第 3 级分类").assertIsDisplayed()
        compose.onNodeWithTag("category-option-breakfast").performClick()
        compose.runOnIdle { assertEquals("breakfast", chosen) }
        compose.onNodeWithText("搜索完整分类路径").performTextInput("早餐")
        compose.onNodeWithText("饮食 › 外食 › 早餐").assertIsDisplayed()
        compose.onNodeWithText("购物 › 早餐").assertIsDisplayed()
        compose.onNodeWithTag("category-option-other-breakfast").performClick()
        compose.runOnIdle { assertEquals("other-breakfast", chosen) }
    }

    @Test fun calendarDateOpensAllDailyRowsAndPreviousDayActuallyChangesFlows() {
        val rows = (1..7).map { row("$it", 2, it * 100L) } + row("previous", 1) + row("future", 3)
        compose.setContent {
            var day by remember { mutableStateOf<LocalDate?>(null) }
            MaterialTheme { Column(Modifier.fillMaxSize()) {
                if (day == null) Column(Modifier.verticalScroll(rememberScrollState())) {
                    SpendingCalendar(YearMonth.of(2026, 9), rows, { "饮食" }) { day = it }
                } else {
                    DateScopeBar(YearMonth.from(day), day, false, {}, { day = it }, {})
                    FlowPage(rows, categories, {}, {}, {}, {}, emptyList(), {}, {}, { _, _ -> }, { _, _ -> },
                        filterDay = day)
                }
            } }
        }
        compose.onNodeWithTag("calendar-day-2").performScrollTo().performClick()
        compose.onNodeWithText("2026-09-02 的流水").assertIsDisplayed()
        compose.onNodeWithTag("flow-list").performScrollToNode(hasText("流水-7"))
        compose.onNodeWithText("流水-7").assertIsDisplayed()
        compose.onNodeWithText("流水-future").assertDoesNotExist()
        compose.onNodeWithTag("previous-period").performClick()
        compose.onNodeWithText("流水-previous").assertIsDisplayed()
        compose.onNodeWithText("流水-7").assertDoesNotExist()
    }

    @Test fun expressionPreviewEqualsButtonAndEditedAmountPersistAsSeven() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ManualLedgerDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val repo = ManualLedgerRepository(db)
            repo.add(NewManualTransaction(stableId = "calc", type = ManualTransactionType.EXPENSE,
                amountText = "1", category = "饮食", account = "现金", note = "计算验收"))
            compose.setContent { MaterialTheme { Column {
                FlowPage(repo.list(), repo.categories(), {}, {}, {}, {}, emptyList(), {}, {}, { id, input ->
                    repo.update(id, input)
                }, { _, _ -> })
            } } }
            compose.onNodeWithText("计算验收").performClick()
            compose.onNodeWithTag("amount-expression").performTextReplacement("3+4")
            compose.onNodeWithText("= ¥7.00").assertIsDisplayed()
            compose.onNodeWithTag("calculator-=").performClick()
            compose.onNodeWithText("保存修改").performClick()
            compose.runOnIdle { assertEquals(700L, repo.list().single().amountCents) }
        } finally { db.close() }
    }

    @Test fun invalidExpressionBlocksSaveAndOperatorButtonsWork() {
        compose.setContent {
            var amount by remember { mutableStateOf("3") }
            MaterialTheme { RecordPage(ManualTransactionType.EXPENSE, amount, "礼金", "现金", "", 1,
                categories, "gift", {}, { amount = it }, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("calculator-+").performClick()
        compose.onNodeWithText("保存到本机").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("amount-expression").performScrollTo().performTextReplacement("3+4")
        compose.onNodeWithText("保存到本机").performScrollTo().assertIsEnabled()
    }
    @Test fun newEntrySavesExpressionWithoutEqualsAndUsesLastCategoryInsteadOfGift() {
        var saved: NewManualTransaction? = null
        compose.setContent {
            MaterialTheme { ManualLedgerScreen(
                rows = listOf(row("recent", 2)), categoryNodes = categories,
                pendingCount = 0, message = "验收", onSave = { saved = it }, onImport = {}, onExport = {},
                onDirectMigration = {}, onDelete = {}, trashRows = emptyList(), onRestore = {}, onEmptyTrash = {},
                onEdit = { _, _ -> }, pendingImport = null, onConfirmImport = {}, onCancelImport = {},
                monthlyBudgetCents = 0, onBudgetChange = {}, syncStatus = ManualSyncStatus(false, "", 0, null),
                onConfigureSync = { _, _ -> }, onDisconnectSync = {}, onSyncNow = {}, onMoveCategory = { _, _ -> },
                onRenameCategory = { _, _ -> }, onCreateCategory = { _, _, _ -> }, onMergeCategories = { _, _ -> },
                onDeleteCategory = {}, onChangeTransactionCategory = { _, _ -> }, skin = LedgerSkin.RED, onSkinChange = {}) }
        }
        compose.onNodeWithContentDescription("记一笔", useUnmergedTree = true).performClick()
        compose.onNodeWithText("饮食 › 外食 › 早餐").assertIsDisplayed()
        compose.onNodeWithTag("amount-expression").performTextInput("3+4")
        compose.onNodeWithText("保存到本机").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("7.00", saved?.amountText)
            assertEquals("breakfast", saved?.categoryId)
        }
    }

}
