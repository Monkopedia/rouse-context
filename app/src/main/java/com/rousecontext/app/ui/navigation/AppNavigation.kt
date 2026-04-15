package com.rousecontext.app.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rousecontext.app.ui.navigation.destinations.addIntegrationDestination
import com.rousecontext.app.ui.navigation.destinations.allClientsDestination
import com.rousecontext.app.ui.navigation.destinations.auditDetailDestination
import com.rousecontext.app.ui.navigation.destinations.auditHistoryDestination
import com.rousecontext.app.ui.navigation.destinations.authApprovalDestination
import com.rousecontext.app.ui.navigation.destinations.healthConnectSetupDestination
import com.rousecontext.app.ui.navigation.destinations.integrationEnabledDestination
import com.rousecontext.app.ui.navigation.destinations.integrationManageDestination
import com.rousecontext.app.ui.navigation.destinations.integrationSetupDestination
import com.rousecontext.app.ui.navigation.destinations.mainDashboardDestination
import com.rousecontext.app.ui.navigation.destinations.notificationPreferencesDestination
import com.rousecontext.app.ui.navigation.destinations.notificationSetupDestination
import com.rousecontext.app.ui.navigation.destinations.onboardingDestination
import com.rousecontext.app.ui.navigation.destinations.outreachSetupDestination
import com.rousecontext.app.ui.navigation.destinations.settingsDestination
import com.rousecontext.app.ui.navigation.destinations.usageSetupDestination
import com.rousecontext.app.ui.screens.SetupMode

object Routes {
    const val ONBOARDING = "onboarding"
    const val NOTIFICATION_PREFERENCES = "onboarding/notification_preferences"
    const val HOME = "home"
    const val AUDIT = "audit?provider={provider}"
    const val AUDIT_BASE = "audit"
    const val SETTINGS = "settings"
    const val ADD_INTEGRATION = "add_integration"
    const val INTEGRATION_MANAGE = "integration/{integrationId}"
    const val INTEGRATION_SETUP = "integration_setup/{integrationId}"
    const val HEALTH_CONNECT_SETUP = "health_connect_setup/{mode}"
    const val NOTIFICATION_SETUP = "notification_setup/{mode}"
    const val OUTREACH_SETUP = "outreach_setup/{mode}"
    const val USAGE_SETUP = "usage_setup/{mode}"
    const val INTEGRATION_ENABLED = "integration_enabled/{integrationId}"
    const val AUTH_APPROVAL = "auth_approval"
    const val ALL_CLIENTS = "all_clients/{integrationId}"
    const val AUDIT_DETAIL = "audit_detail/{entryId}"

    fun allClients(integrationId: String): String = "all_clients/$integrationId"
    fun audit(provider: String? = null): String =
        if (provider != null) "audit?provider=$provider" else "audit"
    fun auditDetail(entryId: Long): String = "audit_detail/$entryId"
    fun integrationManage(id: String): String = "integration/$id"
    fun integrationSetup(id: String): String = "integration_setup/$id"
    fun integrationEnabled(id: String): String = "integration_enabled/$id"
    fun healthConnectSetup(mode: SetupMode): String = "health_connect_setup/${mode.name}"
    fun notificationSetup(mode: SetupMode): String = "notification_setup/${mode.name}"
    fun outreachSetup(mode: SetupMode): String = "outreach_setup/${mode.name}"
    fun usageSetup(mode: SetupMode): String = "usage_setup/${mode.name}"
}

private val ONBOARDING_ROUTES = setOf(
    Routes.ONBOARDING,
    Routes.NOTIFICATION_PREFERENCES
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    startDestination: String = Routes.HOME,
    navController: NavHostController = rememberNavController()
) {
    val controller = remember { NavBarControllerImpl() }
    val currentEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentEntry?.destination?.route
    val isOnboarding = currentRoute in ONBOARDING_ROUTES
    val selectedTab = TAB_INDEX[currentRoute] ?: -1

    CompositionLocalProvider(LocalNavBarController provides controller) {
        Scaffold(
            // Explicitly use safeDrawing insets so the content PaddingValues
            // always include the bottom gesture-navigation inset, even when
            // the bottom NavigationBar is hidden (e.g. on detail screens with
            // a bottom-anchored action button). Without this the default
            // behaviour dropped the bottom inset whenever bottomBar measured
            // as 0-height, clipping buttons behind the gesture bar.
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                if (!isOnboarding && controller.showTopBar) {
                    AppTopBar(controller)
                }
            },
            bottomBar = {
                // Only populate the bottomBar slot when the navigation bar is
                // actually shown. A populated-but-zero-height bottomBar (e.g.
                // an AnimatedVisibility that is hidden) causes Material3
                // Scaffold to ignore the system bottom inset when computing
                // content padding, which clipped bottom-anchored buttons
                // behind the gesture bar on gesture-nav devices.
                if (!isOnboarding && controller.showBottomBar) {
                    AppBottomBar(navController, selectedTab)
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding)
            ) {
                onboardingDestination(navController)
                notificationPreferencesDestination(navController)
                mainDashboardDestination(navController)
                auditHistoryDestination(navController)
                settingsDestination(navController)
                addIntegrationDestination(navController)
                integrationManageDestination(navController)
                allClientsDestination(navController)
                integrationSetupDestination(navController)
                healthConnectSetupDestination(navController)
                notificationSetupDestination(navController)
                outreachSetupDestination(navController)
                usageSetupDestination(navController)
                integrationEnabledDestination(navController)
                authApprovalDestination(navController)
                auditDetailDestination(navController)
            }
        }
    }
}
