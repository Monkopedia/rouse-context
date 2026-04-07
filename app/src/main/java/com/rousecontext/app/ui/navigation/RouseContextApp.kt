package com.rousecontext.app.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.rousecontext.app.state.ThemeMode
import com.rousecontext.app.state.ThemePreference
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.koin.compose.koinInject

/**
 * Top-level composable that applies the app theme and hosts navigation.
 *
 * @param startDestination the initial route, determined by [MainActivity] before
 *   the splash screen is dismissed so there is no flash of the wrong screen.
 */
@Composable
fun RouseContextApp(startDestination: String = Routes.HOME) {
    val themePreference: ThemePreference = koinInject()
    val themeMode by themePreference.themeMode.collectAsState(initial = ThemeMode.AUTO)
    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> systemDark
    }

    // Update status bar icons to match theme
    val activity = LocalContext.current as? ComponentActivity
    androidx.compose.runtime.SideEffect {
        activity?.enableEdgeToEdge(
            statusBarStyle = if (isDark) {
                SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                )
            }
        )
    }

    RouseContextTheme(themeMode = themeMode) {
        AppNavigation(startDestination = startDestination)
    }
}
