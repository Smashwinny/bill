package com.hulk.manualledger

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class RecycleBinUiTest {
    @get:Rule val compose = createComposeRule()
    private val row = ManualTransactionEntity(
        "ui-trash", ManualTransactionType.EXPENSE, 1234, category = "餐饮",
        account = "现金", targetAccount = null, occurredAtMs = 1700000000000,
        note = "验收流水", createdAtMs = 1, updatedAtMs = 1,
    )

    @Test
    fun deleteRequiresTwoConfirmationsAndEitherStepCanBeCancelled() {
        var deleted = 0
        compose.setContent {
            MaterialTheme { Column {
                FlowPage(listOf(row), emptyList(), {}, {}, {}, { deleted++ }, emptyList(), {}, {}, { _, _ -> }, { _, _ -> })
            } }
        }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("继续确认").performClick()
        compose.onNodeWithText("再次确认移入回收站").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("删除").performClick()
        compose.onNodeWithText("继续确认").performClick()
        compose.runOnIdle { assertEquals(0, deleted) }
        compose.onNodeWithText("确认移入回收站").performClick()
        compose.runOnIdle { assertEquals(1, deleted) }
    }

    @Test
    fun trashOffersRestoreAndPurgeRequiresTwoConfirmations() {
        var restored = ""
        var purged = emptyList<String>()
        compose.setContent {
            MaterialTheme { Column {
                FlowPage(emptyList(), emptyList(), {}, {}, {}, {}, listOf(row.copy(deletedAtMs = 2)),
                    { restored = it }, { purged = it }, { _, _ -> }, { _, _ -> })
            } }
        }
        compose.onNodeWithText("回收站（1）").performClick()
        compose.onNodeWithText("恢复这条流水").performClick()
        compose.runOnIdle { assertEquals(row.id, restored) }
        compose.onNodeWithText("清空回收站").performClick()
        compose.onNodeWithText("继续确认").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), purged) }
        compose.onNodeWithText("清空回收站").performClick()
        compose.onNodeWithText("继续确认").performClick()
        compose.runOnIdle { assertEquals(emptyList<String>(), purged) }
        compose.onNodeWithText("永久删除，无法恢复").performClick()
        compose.runOnIdle { assertEquals(listOf(row.id), purged) }
    }
}
