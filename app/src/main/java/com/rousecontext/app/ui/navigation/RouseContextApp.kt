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
 */
@Composable
fun RouseContextApp() {
    val themePreference: ThemePreference = koinInject()
    val themeMode by themePreference.themeMode.collectAsState(initial = ThemeMode.AUTO)

    RouseContextTheme(themeMode = themeMode) {
        AppNavigation()
    }
}
