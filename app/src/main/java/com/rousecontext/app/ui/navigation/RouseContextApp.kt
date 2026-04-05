package com.rousecontext.app.ui.navigation

import androidx.compose.runtime.Composable
import com.rousecontext.app.ui.theme.RouseContextTheme

/**
 * Top-level composable that applies the app theme and hosts navigation.
 */
@Composable
fun RouseContextApp() {
    RouseContextTheme {
        AppNavigation()
    }
}
