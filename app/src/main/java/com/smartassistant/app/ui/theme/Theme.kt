package com.smartassistant.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.smartassistant.app.R

object AppColors {
    val NavyDark = Color(0xFF0B1D3A); val DeepBlue = Color(0xFF132B52)
    val PrimaryBlue = Color(0xFF2563EB); val CyanAccent = Color(0xFF06B6D4)
    val GoldAccent = Color(0xFFF59E0B); val GreenSuccess = Color(0xFF10B981)
    val RedDanger = Color(0xFFEF4444); val PurpleAI = Color(0xFF8B5CF6)
    val Gray = Color(0xFF6B7280); val BgLight = Color(0xFFF5F7FB)
    val DarkBg = Color(0xFF081226); val DarkCard = Color(0xFF0F2242)
    val DarkSurfaceText = Color(0xFFC7D2E4)
}

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
)

private val SmartShapes = Shapes(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

private fun appTypography(): Typography {
    val b = Typography()
    return b.copy(
        headlineLarge = b.headlineLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        headlineMedium = b.headlineMedium.copy(fontFamily = Tajawal, fontWeight = FontWeight.Bold),
        titleLarge = b.titleLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.SemiBold),
        titleMedium = b.titleMedium.copy(fontFamily = Tajawal, fontWeight = FontWeight.Medium),
        bodyLarge = b.bodyLarge.copy(fontFamily = Tajawal),
        bodyMedium = b.bodyMedium.copy(fontFamily = Tajawal),
        labelLarge = b.labelLarge.copy(fontFamily = Tajawal, fontWeight = FontWeight.Medium),
    )
}

private val LightColors = lightColorScheme(
    primary = AppColors.PrimaryBlue, onPrimary = Color.White,
    secondary = AppColors.CyanAccent, tertiary = AppColors.PurpleAI,
    background = AppColors.BgLight, surface = Color.White,
    onBackground = AppColors.NavyDark, onSurface = AppColors.NavyDark,
    onSurfaceVariant = AppColors.Gray, error = AppColors.RedDanger,
)
private val DarkColors = darkColorScheme(
    primary = AppColors.PrimaryBlue, onPrimary = Color.White,
    primaryContainer = AppColors.DeepBlue, secondary = AppColors.CyanAccent,
    tertiary = AppColors.PurpleAI, background = AppColors.DarkBg,
    surface = AppColors.DarkCard, surfaceVariant = AppColors.DeepBlue,
    onBackground = Color.White, onSurface = Color.White,
    onSurfaceVariant = AppColors.DarkSurfaceText, error = AppColors.RedDanger,
)

enum class ThemeMode { LIGHT, DARK, SYSTEM }

@Composable
fun SmartAssistantTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.LIGHT -> false; ThemeMode.DARK -> true; ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors,
            typography = appTypography(), shapes = SmartShapes, content = content)
    }
}
