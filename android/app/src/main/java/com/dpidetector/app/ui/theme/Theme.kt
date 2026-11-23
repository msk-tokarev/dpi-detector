package com.dpidetector.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0B0F14)
    val Surface = Color(0xFF131A23)
    val SurfaceAlt = Color(0xFF1A2330)
    val Border = Color(0xFF243041)

    val TextPrimary = Color(0xFFE9EEF5)
    val TextSecondary = Color(0xFF8C9BB0)
    val TextMuted = Color(0xFF5D6D82)

    val Good = Color(0xFF2EE59D)
    val GoodBg = Color(0x1F2EE59D)

    val Bad = Color(0xFFFF5C6C)
    val BadBg = Color(0x22FF5C6C)

    val Warn = Color(0xFFFFB84D)
    val WarnBg = Color(0x22FFB84D)

    val Accent = Color(0xFF4CC9F0)
    val AccentBg = Color(0x224CC9F0)

    val Neutral = Color(0xFF7B8CA3)
    val NeutralBg = Color(0x1F7B8CA3)
}

private val DarkScheme = darkColorScheme(
    primary = Palette.Good,
    onPrimary = Color(0xFF042619),
    primaryContainer = Color(0xFF123B2D),
    onPrimaryContainer = Palette.Good,
    secondary = Palette.Accent,
    onSecondary = Color(0xFF03232E),
    secondaryContainer = Color(0xFF123442),
    onSecondaryContainer = Palette.Accent,
    tertiary = Palette.Warn,
    error = Palette.Bad,
    onError = Color(0xFF2E040A),
    errorContainer = Color(0xFF42151C),
    onErrorContainer = Palette.Bad,
    background = Palette.Bg,
    onBackground = Palette.TextPrimary,
    surface = Palette.Surface,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceAlt,
    onSurfaceVariant = Palette.TextSecondary,
    outline = Palette.Border,
    outlineVariant = Color(0xFF1B2533),
)

val MonoFamily = FontFamily.Monospace

private val AppTypography = Typography(
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp,
        color = Palette.TextPrimary,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        color = Palette.TextPrimary,
    ),
    titleSmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.3.sp,
        color = Palette.TextSecondary,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Palette.TextPrimary,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Palette.TextSecondary,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun DpiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        content = content,
    )
}
