package com.rousecontext.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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

    RouseContextTheme(themeMode = themeMode) {
        AppNavigation(startDestination = startDestination)
    }
}
