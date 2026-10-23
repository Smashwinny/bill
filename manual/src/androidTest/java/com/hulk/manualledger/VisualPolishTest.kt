package com.hulk.manualledger

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.YearMonth
import java.time.ZoneId

/** Synthetic UI only: callbacks do not save and preferences have a dedicated test namespace. */
class VisualPolishTest {
    @get:Rule val compose = createComposeRule()
    private val month = YearMonth.now()
    private val context by lazy {
        object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences("test_visual_$name", mode)
        }
    }
    @After fun cleanup() {
        listOf("manual_exchange_rates", "manual_entry").forEach { context.baseContext.deleteSharedPreferences("test_visual_$it") }
    }
    private fun rows(): List<ManualTransactionEntity> =
        listOf("饮食", "交通", "购物", "居家", "休闲", "健康").flatMapIndexed { group, name ->
            (1..(12 - group * 2).coerceAtLeast(1)).map { index ->
                ManualTransactionEntity("$group-$index", ManualTransactionType.EXPENSE,
                    if (name == "居家") 300000 else 1200L + index * 105, category = name, categoryId = name,
                    account = "日常账户", targetAccount = null,
                    occurredAtMs = month.atDay(index * 2).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    note = null, createdAtMs = index.toLong(), updatedAtMs = 1)
            }
        }
    private fun capture(name: String) {
        compose.waitForIdle()
        File(context.cacheDir, "polish-$name.png").outputStream().use {
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
    @Test fun frequencyCardUsesCountsInsteadOfAmountsAndCanExpand() {
        val frequency = LedgerBrowsing.expenseFrequency(rows(), month, { it.category })
        compose.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            CategoryFrequencyCard(frequency) {}
        } } }
        compose.onNodeWithTag("frequency-rank-0").assert(hasAnyDescendant(hasText("饮食")))
        compose.onNodeWithTag("frequency-rank-0").assert(hasAnyDescendant(hasText("12 笔")))
        compose.onNodeWithTag("frequency-rank-3").performScrollTo().assert(hasAnyDescendant(hasText("居家")))
        compose.onNodeWithText("查看全部 6 类").performScrollTo().performClick()
        compose.onNodeWithTag("frequency-rank-5").performScrollTo().assert(hasAnyDescendant(hasText("健康")))
        compose.onNodeWithText("收起分类").performScrollTo().performClick()
        compose.onNodeWithTag("frequency-rank-5").assertDoesNotExist()
    }
    @Test fun calendarLegendShowsFrequencyOrderAndMonthlyCounts() {
        compose.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SpendingCalendar(month, rows(), { it.category }) {}
        } } }
        compose.onNodeWithTag("calendar-legend-饮食").performScrollTo().assert(hasAnyDescendant(hasText("12 笔")))
        val food = compose.onNodeWithTag("calendar-legend-饮食").fetchSemanticsNode().boundsInRoot
        val home = compose.onNodeWithTag("calendar-legend-居家").fetchSemanticsNode().boundsInRoot
        assertTrue(food.top < home.top)
        compose.onNodeWithTag("calendar-legend-健康").assertDoesNotExist()
        compose.onNodeWithText("全部 6 类图例").performScrollTo().performClick()
        compose.onNodeWithTag("calendar-legend-健康").performScrollTo().assertIsDisplayed()
    }
    @Test fun bothSkinsAndMainPagesHaveDeviceVisualEvidence() {
        context.getSharedPreferences("manual_exchange_rates", Context.MODE_PRIVATE).edit()
            .putLong("attempted", System.currentTimeMillis()).commit()
        var skin by mutableStateOf(LedgerSkin.GREEN)
        var fontScale by mutableStateOf(1f)
        val rows = rows()
        val categories = rows.map { it.category }.distinct().map {
            LedgerCategoryEntity(it, ManualTransactionType.EXPENSE, it, null, 0, false, 1, 1)
        }
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context, LocalDensity provides Density(density, fontScale)) {
                LedgerTheme(skin) { ManualLedgerScreen(rows, categories, 0, "", {}, {}, {}, {}, {}, emptyList(), {}, {},
                    { _, _ -> }, null, {}, {}, 0, {}, ManualSyncStatus(false, "", 0, null),
                    { _, _ -> }, {}, {}, { _, _ -> }, { _, _ -> }, { _, _, _ -> }, { _, _ -> }, {},
                    { _, _ -> }, skin, {}) }
            }
        }
        capture("overview-green")
        compose.runOnIdle { skin = LedgerSkin.RED }
        capture("overview-red")
        compose.onNodeWithTag("overview-list").performScrollToNode(hasTestTag("category-frequency"))
        capture("frequency")
        compose.onNodeWithContentDescription("记一笔", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("amount-expression").assertIsDisplayed()
        capture("record")
        compose.onNodeWithContentDescription("流水", useUnmergedTree = true).performClick()
        capture("flow")
        compose.onNodeWithContentDescription("分析", useUnmergedTree = true).performClick()
        capture("analysis")
        compose.runOnIdle { fontScale = 1.3f }
        capture("analysis-large-font")
    }
}
