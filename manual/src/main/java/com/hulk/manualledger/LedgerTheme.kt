package com.hulk.manualledger

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Neutral paper surfaces keep category colors and financial figures in focus.
internal val LedgerColorScheme = lightColorScheme(
    primary = Color(0xFF286454), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2EDE7), onPrimaryContainer = Color(0xFF214C40),
    secondary = Color(0xFF68716C), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEAEDE8), onSecondaryContainer = Color(0xFF34453D),
    tertiary = Color(0xFFA5824E),
    background = Color(0xFFF5F4F0), onBackground = Color(0xFF242D29),
    surface = Color(0xFFFFFEFB), onSurface = Color(0xFF242D29),
    surfaceVariant = Color(0xFFEEEFE9), onSurfaceVariant = Color(0xFF626B65),
    outline = Color(0xFF9DA79F), outlineVariant = Color(0xFFE4E6DF),
    surfaceTint = Color(0xFF286454), error = Color(0xFFB43B3B),
)

internal val RedLedgerColorScheme = LedgerColorScheme.copy(
    primary = Color(0xFF9A4553), primaryContainer = Color(0xFFF3E5E6),
    onPrimaryContainer = Color(0xFF6F2E3B), secondary = Color(0xFF76666A),
    secondaryContainer = Color(0xFFF0E7E7), onSecondaryContainer = Color(0xFF603C43),
    surfaceTint = Color(0xFF9A4553),
)

private val LedgerTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-.6).sp, fontFeatureSettings = "tnum"),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 29.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp, fontFeatureSettings = "tnum"),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontFeatureSettings = "tnum"),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp),
)

@Composable
internal fun LedgerTheme(skin: LedgerSkin = LedgerSkin.GREEN, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (skin == LedgerSkin.RED) RedLedgerColorScheme else LedgerColorScheme,
        typography = LedgerTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}

internal fun categoryIcon(name: String): ImageVector = when {
    listOf("饮食", "食品", "餐饮", "早餐", "午餐", "晚餐").any(name::contains) -> LedgerIcons.Food
    listOf("交通", "行车", "出行").any(name::contains) -> LedgerIcons.Transit
    listOf("购物", "服饰", "衣服").any(name::contains) -> LedgerIcons.Bag
    listOf("居家", "住房", "房租").any(name::contains) -> LedgerIcons.Home
    listOf("礼", "人情", "社交").any(name::contains) -> LedgerIcons.Gift
    listOf("医疗", "健康").any(name::contains) -> LedgerIcons.Heart
    listOf("娱乐", "休闲").any(name::contains) -> LedgerIcons.Leisure
    listOf("工资", "收入").any(name::contains) -> LedgerIcons.Wallet
    listOf("教育", "学习", "书", "培训").any(name::contains) -> LedgerIcons.Book
    listOf("旅游", "旅行", "酒店").any(name::contains) -> LedgerIcons.Travel
    listOf("宠物", "猫", "狗").any(name::contains) -> LedgerIcons.Pet
    else -> LedgerIcons.Category
}

@Composable
internal fun CategoryBadge(name: String) {
    val root = name.substringBefore(CategoryCatalog.HIERARCHY_SEPARATOR)
    val color = ledgerCategoryColor(root)
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .11f),
        border = BorderStroke(.5.dp, color.copy(alpha = .12f))) {
        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            Icon(categoryIcon(root), contentDescription = null, tint = color, modifier = Modifier.size(21.dp))
        }
    }
}
