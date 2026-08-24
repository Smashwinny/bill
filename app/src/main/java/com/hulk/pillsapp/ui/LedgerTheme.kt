package com.hulk.pillsapp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LedgerLightColors = lightColorScheme(
    primary = Color(0xFF0C6B58),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3E7),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4D635B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD0E8DE),
    onSecondaryContainer = Color(0xFF0A1F19),
    tertiary = Color(0xFF3F6374),
    tertiaryContainer = Color(0xFFC3E8FC),
    background = Color(0xFFF5F8F6),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDFE5E1),
    onSurfaceVariant = Color(0xFF404945),
    outline = Color(0xFF707974),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val LedgerDarkColors = darkColorScheme(
    primary = Color(0xFF70D7BE),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF005141),
    onPrimaryContainer = Color(0xFF8CF4D9),
    secondary = Color(0xFFB4CCC2),
    secondaryContainer = Color(0xFF354B43),
    tertiary = Color(0xFFA7CCE0),
    tertiaryContainer = Color(0xFF274B5C),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE5E1),
    surface = Color(0xFF171D1A),
    onSurface = Color(0xFFDFE5E1),
    surfaceVariant = Color(0xFF404945),
    onSurfaceVariant = Color(0xFFBFC9C3),
    outline = Color(0xFF89938E),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

private val LedgerTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

@Composable
fun AutomaticLedgerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) LedgerDarkColors else LedgerLightColors,
        typography = LedgerTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp),
            extraLarge = RoundedCornerShape(32.dp),
        ),
        content = content,
    )
}

