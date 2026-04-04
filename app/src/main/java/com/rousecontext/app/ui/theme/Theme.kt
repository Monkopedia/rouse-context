package com.rousecontext.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colors
val TealPrimary = Color(0xFF1D9E75)
val TealLight = Color(0xFF5DCAA5)
val TealDeep = Color(0xFF0F6E56)
val AmberAccent = Color(0xFFEF9F27)
val AmberDark = Color(0xFFBA7517)
val BackgroundBlack = Color(0xFF111111)
val BackgroundNavy = Color(0xFF0A1628)
val BackgroundTealDark = Color(0xFF0D1A16)

private val DarkColorScheme = darkColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = TealDeep,
    onPrimaryContainer = TealLight,
    secondary = AmberAccent,
    onSecondary = Color.Black,
    secondaryContainer = AmberDark,
    onSecondaryContainer = Color(0xFFFFE0A0),
    tertiary = TealLight,
    onTertiary = Color.Black,
    background = BackgroundBlack,
    onBackground = Color(0xFFE2E2E2),
    surface = BackgroundNavy,
    onSurface = Color(0xFFE2E2E2),
    surfaceVariant = Color(0xFF1A2A3A),
    onSurfaceVariant = Color(0xFFBBCCDD),
    error = Color(0xFFFF6B6B),
    onError = Color.Black,
    outline = Color(0xFF3A4A5A),
    outlineVariant = Color(0xFF2A3A4A),
)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F0DA),
    onPrimaryContainer = TealDeep,
    secondary = AmberDark,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE0A0),
    onSecondaryContainer = Color(0xFF3A2800),
    tertiary = TealDeep,
    onTertiary = Color.White,
    background = Color(0xFFF8FAF9),
    onBackground = Color(0xFF1A1C1B),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1B),
    surfaceVariant = Color(0xFFE8F5EF),
    onSurfaceVariant = Color(0xFF404943),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    outline = Color(0xFF6F7973),
    outlineVariant = Color(0xFFBFC9C2),
)

@Composable
fun RouseContextTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        content = content,
    )
}
