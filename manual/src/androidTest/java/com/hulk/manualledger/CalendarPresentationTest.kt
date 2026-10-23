package com.hulk.manualledger

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.Assert.assertNotEquals
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class CalendarPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cityCalendarRotatesAndSelectsVisibleDatePlaquesWithFlatFallback() {
        val names = listOf("饮食", "交通", "购物", "居家")
        val rows = (1..28).flatMap { day ->
            val count = listOf(1,2,3,4,5,7,8,10,12,16,0,2)[(day-1)%12]
            (0 until count).map { index ->
            val name = names[(index + day) % names.size]
            ManualTransactionEntity("$day-$index", ManualTransactionType.EXPENSE, ((day * (index + 3)) % 19 + 1) * 100L,
                category = name, account = "测试", targetAccount = null,
                occurredAtMs = LocalDate.of(2026, 2, day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                note = null, createdAtMs = 1, updatedAtMs = 1)
        } }
        var selected: LocalDate? = null
        compose.setContent { LedgerTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            SpendingCalendar(YearMonth.of(2026, 2), rows, { it.category }, { selected = it })
        } } }
        val context = ApplicationProvider.getApplicationContext<Context>()
        fun capture(name: String) {
            File(context.cacheDir, name).outputStream().use {
                compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("calendar-3d.png")
        val scene = compose.onNodeWithTag("calendar-sandtable")
        val original = scene.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        scene.performTouchInput { swipe(center, center + Offset(120f, -100f), 600) }
        assertNotEquals(original, scene.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        capture("sandtable-rotated.png")
        compose.onNodeWithTag("reset-sandtable").performClick()
        assertEquals(original, scene.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        val bounds = scene.fetchSemanticsNode().boundsInRoot
        val point = CalendarProjection(bounds.width, bounds.height, -22f, 52f, 1f)
            .project(CityCalendarModel.marker(YearMonth.of(2026, 2), 28, 4, 16))
        scene.performTouchInput { click(Offset(point.x,point.y)) }
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 2, 28), selected) }
        compose.onNodeWithTag("calendar-perspective").performClick()
        compose.onNodeWithText("平面").assertIsDisplayed()
        capture("calendar-flat.png")
        compose.onNodeWithTag("calendar-day-28").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("calendar-day-29").assertDoesNotExist()
    }
}
