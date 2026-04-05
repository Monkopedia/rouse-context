package com.rousecontext.app.ui.navigation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rousecontext.app.ui.screens.AddIntegrationPickerScreen
import com.rousecontext.app.ui.screens.AuditHistoryScreen
import com.rousecontext.app.ui.screens.DeviceCodeApprovalScreen
import com.rousecontext.app.ui.screens.HealthConnectSetupScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.screens.OnboardingErrorScreen
import com.rousecontext.app.ui.screens.SettingUpScreen
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.SettingsScreen
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.DeviceCodeApprovalViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val WELCOME = "welcome"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val AUDIT = "audit"
    const val SETTINGS = "settings"
    const val ADD_INTEGRATION = "add_integration"
    const val INTEGRATION_MANAGE = "integration/{integrationId}"
    const val INTEGRATION_SETUP = "integration_setup/{integrationId}"
    const val HEALTH_CONNECT_SETUP = "health_connect_setup"
    const val INTEGRATION_ENABLED = "integration_enabled/{integrationId}"
    const val DEVICE_CODE = "device_code/{integrationId}"

    fun integrationManage(id: String): String = "integration/$id"
    fun integrationSetup(id: String): String = "integration_setup/$id"
    fun integrationEnabled(id: String): String = "integration_enabled/$id"
    fun deviceCode(id: String): String = "device_code/$id"
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val onboardingViewModel: OnboardingViewModel = koinViewModel()
    val onboardingState by onboardingViewModel.state.collectAsState()

    // Wait until the initial check completes before rendering navigation
    if (onboardingState is OnboardingState.Checking) return

    val startDestination = when (onboardingState) {
        is OnboardingState.Onboarded -> Routes.HOME
        else -> Routes.WELCOME
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                onGetStarted = {
                    onboardingViewModel.startOnboarding()
                    navController.navigate(Routes.ONBOARDING) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.ONBOARDING) {
            val state by onboardingViewModel.state.collectAsState()

            // Navigate to dashboard on success
            LaunchedEffect(state) {
                if (state is OnboardingState.Onboarded) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            }

            when (state) {
                is OnboardingState.InProgress -> {
                    SettingUpScreen(
                        state = SettingUpState(SettingUpVariant.FirstTime),
                        onCancel = {
                            navController.navigate(Routes.WELCOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }
                is OnboardingState.RateLimited -> {
                    val rateLimited = state as OnboardingState.RateLimited
                    SettingUpScreen(
                        state = SettingUpState(
                            SettingUpVariant.RateLimited(rateLimited.retryDate)
                        ),
                        onCancel = {
                            navController.navigate(Routes.WELCOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }
                is OnboardingState.Failed -> {
                    val failed = state as OnboardingState.Failed
                    OnboardingErrorScreen(
                        message = failed.message,
                        onRetry = { onboardingViewModel.retry() },
                        onBack = {
                            navController.navigate(Routes.WELCOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }
                else -> {
                    // Checking, NotOnboarded, Onboarded handled by LaunchedEffect above
                    SettingUpScreen(
                        state = SettingUpState(SettingUpVariant.FirstTime),
                        onCancel = {
                            navController.navigate(Routes.WELCOME) {
                                popUpTo(Routes.ONBOARDING) { inclusive = true }
                            }
                        }
                    )
                }
            }
        }

        composable(Routes.HOME) {
            val viewModel: MainDashboardViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            MainDashboardScreen(
                state = state,
                selectedTab = 0,
                onAddIntegration = { navController.navigate(Routes.ADD_INTEGRATION) },
                onIntegrationClick = { id -> navController.navigate(Routes.integrationManage(id)) },
                onViewAllActivity = { navController.navigate(Routes.AUDIT) },
                onTabSelected = { tab ->
                    when (tab) {
                        1 -> navController.navigate(Routes.AUDIT)
                        2 -> navController.navigate(Routes.SETTINGS)
                    }
                }
            )
        }

        composable(Routes.AUDIT) {
            val viewModel: AuditHistoryViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            AuditHistoryScreen(
                state = state,
                onProviderFilterChanged = viewModel::setProviderFilter,
                onDateFilterChanged = viewModel::setDateFilter,
                onClearHistory = viewModel::clearHistory,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                        2 -> navController.navigate(Routes.SETTINGS)
                    }
                }
            )
        }

        composable(Routes.SETTINGS) {
            val viewModel: SettingsViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            SettingsScreen(
                state = state,
                onIdleTimeoutChanged = viewModel::setIdleTimeout,
                onPostSessionModeChanged = viewModel::setPostSessionMode,
                onTabSelected = { tab ->
                    when (tab) {
                        0 -> navController.navigate(Routes.HOME) {
                            popUpTo(Routes.HOME) { inclusive = true }
                        }
                        1 -> navController.navigate(Routes.AUDIT)
                    }
                }
            )
        }

        composable(Routes.ADD_INTEGRATION) {
            val viewModel: AddIntegrationViewModel = koinViewModel()
            val integrations by viewModel.pickerIntegrations.collectAsState()
            AddIntegrationPickerScreen(
                integrations = integrations,
                onSetUp = { id ->
                    if (id == HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID) {
                        navController.navigate(Routes.HEALTH_CONNECT_SETUP)
                    } else {
                        navController.navigate(Routes.integrationSetup(id))
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.INTEGRATION_MANAGE,
            arguments = listOf(navArgument("integrationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val integrationId = backStackEntry.arguments
                ?.getString("integrationId") ?: return@composable
            val viewModel: IntegrationManageViewModel = koinViewModel()
            LaunchedEffect(integrationId) { viewModel.loadIntegration(integrationId) }
            val state by viewModel.state.collectAsState()
            IntegrationManageScreen(
                state = state,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.INTEGRATION_SETUP,
            arguments = listOf(navArgument("integrationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val integrationId = backStackEntry.arguments
                ?.getString("integrationId") ?: return@composable
            val viewModel: IntegrationSetupViewModel = koinViewModel()
            LaunchedEffect(integrationId) { viewModel.startSetup(integrationId) }
            val state by viewModel.state.collectAsState()
            SettingUpScreen(
                state = state,
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.HEALTH_CONNECT_SETUP) {
            val viewModel: HealthConnectSetupViewModel = koinViewModel()
            val requestPermissions = rememberLauncherForActivityResult(
                contract = PermissionController.createRequestPermissionResultContract()
            ) { granted ->
                val enabled = viewModel.onPermissionsResult(granted)
                if (enabled) {
                    navController.navigate(
                        Routes.integrationEnabled(
                            HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID
                        )
                    ) {
                        popUpTo(Routes.ADD_INTEGRATION) { inclusive = true }
                    }
                }
            }
            HealthConnectSetupScreen(
                onGrantAccess = {
                    requestPermissions.launch(HEALTH_CONNECT_PERMISSIONS)
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(
            route = Routes.INTEGRATION_ENABLED,
            arguments = listOf(navArgument("integrationId") { type = NavType.StringType })
        ) { backStackEntry ->
            val integrationId = backStackEntry.arguments
                ?.getString("integrationId") ?: return@composable
            IntegrationEnabledScreen(
                state = IntegrationEnabledState(
                    integrationName = when (integrationId) {
                        HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID -> "Health Connect"
                        else -> integrationId
                    }
                ),
                onCancel = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.DEVICE_CODE,
            arguments = listOf(navArgument("integrationId") { type = NavType.StringType })
        ) {
            val viewModel: DeviceCodeApprovalViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            DeviceCodeApprovalScreen(
                state = state,
                onCodeChanged = viewModel::onCodeChanged,
                onApprove = viewModel::approve,
                onDeny = viewModel::deny
            )
        }
    }
}

/**
 * Health Connect permissions requested during setup.
 * Must match the permissions declared in the health module's AndroidManifest.xml.
 */
private val HEALTH_CONNECT_PERMISSIONS = setOf(
    HealthPermission.getReadPermission(StepsRecord::class),
    HealthPermission.getReadPermission(HeartRateRecord::class),
    HealthPermission.getReadPermission(SleepSessionRecord::class)
)
