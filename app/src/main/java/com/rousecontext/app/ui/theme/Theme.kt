package com.rousecontext.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.rousecontext.app.state.ThemeMode

// Brand colors - navy + amber palette
val NavyDark = Color(0xFF0A1628) // primary dark, status bar
val NavyPrimary = Color(0xFF1A2744) // lighter navy for surfaces
val NavyLight = Color(0xFF2A3A5C) // elevated surfaces, cards on dark
val AmberAccent = Color(0xFFEF9F27) // interactive: buttons, highlights, FABs
val AmberDark = Color(0xFFBA7517) // darker amber for light-mode secondary
val SurfaceDark = Color(0xFF0F1C32) // dark mode surface
val WarmWhite = Color(0xFFFAF8F4) // light mode background
val SurfaceLight = Color(0xFFF5F6FA) // light mode surface
val WarningContainerDark = Color(0xFF3A2800) // dark warning card background
val OnWarningContainerDark = Color(0xFFFFE0A0) // dark warning card text
val WarningContainerLight = Color(0xFFFFF3E0) // light warning card background
val OnWarningContainerLight = Color(0xFF5D4200) // light warning card text
val SuccessGreen = Color(0xFF4CAF50) // status: verified, connected
val AlertContainerDark = Color(0xFF3A0000) // dark alert card background
val AlertContainerLight = Color(0xFFFFEBEE) // light alert card background
val AlertContentDark = Color(0xFFFF6B6B) // dark alert text/icon
val AlertContentLight = Color(0xFFBA1A1A) // light alert text/icon
val CodeBoxBorderEmpty = Color(0xFF404950) // light-mode empty code box border

private val DarkColorScheme = darkColorScheme(
    primary = AmberAccent,
    onPrimary = Color.Black,
    primaryContainer = NavyLight,
    onPrimaryContainer = Color(0xFFD0D8E8),
    secondary = AmberAccent,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF2A3040),
    onSecondaryContainer = Color(0xFFFFE0A0),
    tertiary = AmberDark,
    onTertiary = Color.Black,
    background = NavyDark,
    onBackground = Color(0xFFE2E2E2),
    surface = SurfaceDark,
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = NavyPrimary,
    onSurfaceVariant = Color(0xFFBBCCDD),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
    outline = Color(0xFF3A4A5A),
    outlineVariant = Color(0xFF2A3A4A)
)

private val LightColorScheme = lightColorScheme(
    primary = NavyDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0D8E8),
    onPrimaryContainer = NavyDark,
    secondary = AmberDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0A0),
    onSecondaryContainer = Color(0xFF3A2800),
    tertiary = NavyLight,
    onTertiary = Color.White,
    background = WarmWhite,
    onBackground = Color(0xFF1A1C1B),
    surface = SurfaceLight,
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE0E4EE),
    onSurfaceVariant = Color(0xFF404950),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF6F7580),
    outlineVariant = Color(0xFFBFC4CE)
)

data class ExtendedColors(
    val warningContainer: Color,
    val onWarningContainer: Color,
    val warningAccent: Color,
    val alertContainer: Color,
    val alertContent: Color,
    val codeBoxBorderEmpty: Color,
    val topBarContainer: Color
)

private val DarkExtendedColors = ExtendedColors(
    warningContainer = WarningContainerDark,
    onWarningContainer = OnWarningContainerDark,
    warningAccent = AmberAccent,
    alertContainer = AlertContainerDark,
    alertContent = AlertContentDark,
    codeBoxBorderEmpty = Color.White.copy(alpha = 0.2f),
    topBarContainer = NavyDark
)

private val LightExtendedColors = ExtendedColors(
    warningContainer = WarningContainerLight,
    onWarningContainer = OnWarningContainerLight,
    warningAccent = AmberDark,
    alertContainer = AlertContainerLight,
    alertContent = AlertContentLight,
    codeBoxBorderEmpty = CodeBoxBorderEmpty,
    topBarContainer = WarmWhite
)

val LocalExtendedColors = staticCompositionLocalOf { DarkExtendedColors }

@Composable
fun RouseContextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> darkTheme
    }
    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme
    val extendedColors = if (isDark) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalExtendedColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            content = content
        )
    }
}
