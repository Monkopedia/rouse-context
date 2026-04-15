package com.rousecontext.app.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors

/**
 * Persistent top app bar driven by [NavBarController]. Animates the title
 * on change and renders the back affordance when the active destination
 * requests one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(controller: NavBarController) {
    TopAppBar(
        title = {
            val custom = controller.titleContent
            if (custom != null) {
                custom()
            } else {
                AnimatedContent(
                    targetState = controller.title,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "titleCrossfade"
                ) { title ->
                    Text(title)
                }
            }
        },
        colors = appBarColors(),
        navigationIcon = {
            if (controller.showBackButton) {
                IconButton(
                    onClick = {
                        controller.onBackPressed?.invoke()
                    }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            }
        }
    )
}

/**
 * Persistent bottom navigation bar. Rendered only on the top-level tab
 * destinations (Home, Audit, Settings); everything else hides it through
 * [NavBarController].
 */
@Composable
internal fun AppBottomBar(navController: NavHostController, selectedTab: Int) {
    NavigationBar(containerColor = navBarContainerColor()) {
        val itemColors = navBarItemColors()
        NavigationBarItem(
            selected = selectedTab == 0,
            onClick = {
                navController.navigate(Routes.HOME) {
                    popUpTo(Routes.HOME) {
                        inclusive = true
                    }
                }
            },
            icon = {
                Icon(
                    Icons.Default.Home,
                    contentDescription = "Home"
                )
            },
            label = { Text("Home") },
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedTab == 1,
            onClick = {
                navController.navigate(Routes.AUDIT_BASE) {
                    popUpTo(Routes.HOME)
                }
            },
            icon = {
                Icon(
                    Icons.Default.History,
                    contentDescription = "Audit"
                )
            },
            label = { Text("Audit") },
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedTab == 2,
            onClick = {
                navController.navigate(Routes.SETTINGS) {
                    popUpTo(Routes.HOME)
                }
            },
            icon = {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = "Settings"
                )
            },
            label = { Text("Settings") },
            colors = itemColors
        )
    }
}
