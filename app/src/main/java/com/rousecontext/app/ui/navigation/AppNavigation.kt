package com.rousecontext.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rousecontext.app.ui.screens.AddIntegrationPickerScreen
import com.rousecontext.app.ui.screens.AuditDetailScreen
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditHistoryScreen
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalScreen
import com.rousecontext.app.ui.screens.DeviceCodeApprovalScreen
import com.rousecontext.app.ui.screens.HealthConnectSetupScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.screens.NotificationSetupScreen
import com.rousecontext.app.ui.screens.OnboardingErrorScreen
import com.rousecontext.app.ui.screens.OutreachSetupScreen
import com.rousecontext.app.ui.screens.SettingUpScreen
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.SettingsScreen
import com.rousecontext.app.ui.screens.UsageSetupScreen
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
import com.rousecontext.app.ui.viewmodels.DeviceCodeApprovalViewModel
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationManageViewModel
import com.rousecontext.app.ui.viewmodels.IntegrationSetupState
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.app.ui.viewmodels.MainDashboardViewModel
import com.rousecontext.app.ui.viewmodels.NotificationSetupViewModel
import com.rousecontext.app.ui.viewmodels.OnboardingState
import com.rousecontext.app.ui.viewmodels.OnboardingViewModel
import com.rousecontext.app.ui.viewmodels.OutreachSetupViewModel
import com.rousecontext.app.ui.viewmodels.SettingsViewModel
import com.rousecontext.app.ui.viewmodels.UsageSetupViewModel
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val AUDIT = "audit"
    const val SETTINGS = "settings"
    const val ADD_INTEGRATION = "add_integration"
    const val INTEGRATION_MANAGE = "integration/{integrationId}"
    const val INTEGRATION_SETUP = "integration_setup/{integrationId}"
    const val HEALTH_CONNECT_SETUP = "health_connect_setup"
    const val NOTIFICATION_SETUP = "notification_setup"
    const val OUTREACH_SETUP = "outreach_setup"
    const val USAGE_SETUP = "usage_setup"
    const val INTEGRATION_ENABLED = "integration_enabled/{integrationId}"
    const val DEVICE_CODE = "device_code/{integrationId}"
    const val AUTH_APPROVAL = "auth_approval"
    const val AUDIT_DETAIL = "audit_detail/{entryId}"

    fun auditDetail(entryId: Long): String = "audit_detail/$entryId"
    fun integrationManage(id: String): String = "integration/$id"
    fun integrationSetup(id: String): String = "integration_setup/$id"
    fun integrationEnabled(id: String): String = "integration_enabled/$id"
    fun deviceCode(id: String): String = "device_code/$id"
}

@Suppress("CyclomaticComplexMethod")
@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME
    ) {
        composable(Routes.ONBOARDING) {
            val onboardingViewModel: OnboardingViewModel = koinViewModel()
            val state by onboardingViewModel.state.collectAsState()

            // Navigate back to dashboard on success
            LaunchedEffect(state) {
                if (state is OnboardingState.Onboarded) {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            }

            when (state) {
                is OnboardingState.Checking, is OnboardingState.NotOnboarded -> {
                    WelcomeScreen(
                        onGetStarted = { onboardingViewModel.startOnboarding() }
                    )
                }
                is OnboardingState.InProgress -> {
                    val step = (state as OnboardingState.InProgress).step
                    SettingUpScreen(
                        state = SettingUpState(SettingUpVariant.FirstTime(step)),
                        onCancel = { navController.popBackStack() }
                    )
                }
                is OnboardingState.RateLimited -> {
                    val rateLimited = state as OnboardingState.RateLimited
                    SettingUpScreen(
                        state = SettingUpState(
                            SettingUpVariant.RateLimited(rateLimited.retryDate)
                        ),
                        onCancel = { navController.popBackStack() }
                    )
                }
                is OnboardingState.Failed -> {
                    val failed = state as OnboardingState.Failed
                    OnboardingErrorScreen(
                        message = failed.message,
                        onRetry = { onboardingViewModel.retry() },
                        onBack = { navController.popBackStack() }
                    )
                }
                is OnboardingState.Onboarded -> {
                    // Handled by LaunchedEffect above; show loading briefly
                    SettingUpScreen(
                        state = SettingUpState(SettingUpVariant.Refreshing),
                        onCancel = { navController.popBackStack() }
                    )
                }
            }
        }

        composable(Routes.HOME) {
            val viewModel: MainDashboardViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()

            // Refresh when returning to dashboard (e.g. after onboarding)
            LaunchedEffect(
                navController.currentBackStackEntry?.lifecycle?.currentState
            ) {
                viewModel.refresh()
            }

            MainDashboardScreen(
                state = state,
                selectedTab = 0,
                onAddIntegration = { navController.navigate(Routes.ADD_INTEGRATION) },
                onIntegrationClick = { id -> navController.navigate(Routes.integrationManage(id)) },
                onViewAllActivity = { navController.navigate(Routes.AUDIT) },
                onPendingAuthRequests = { navController.navigate(Routes.AUTH_APPROVAL) },
                onSetUp = { navController.navigate(Routes.ONBOARDING) },
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
                onEntryClick = { entryId ->
                    navController.navigate(Routes.auditDetail(entryId))
                },
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
                onThemeModeChanged = viewModel::setThemeMode,
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
                    when (id) {
                        HealthConnectSetupViewModel.HEALTH_INTEGRATION_ID ->
                            navController.navigate(Routes.HEALTH_CONNECT_SETUP)
                        NotificationSetupViewModel.INTEGRATION_ID ->
                            navController.navigate(Routes.NOTIFICATION_SETUP)
                        OutreachSetupViewModel.INTEGRATION_ID ->
                            navController.navigate(Routes.OUTREACH_SETUP)
                        UsageSetupViewModel.INTEGRATION_ID ->
                            navController.navigate(Routes.USAGE_SETUP)
                        else ->
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
            when (state) {
                is IntegrationSetupState.Complete -> {
                    LaunchedEffect(Unit) {
                        navController.navigate(Routes.integrationEnabled(integrationId)) {
                            popUpTo(Routes.ADD_INTEGRATION) { inclusive = true }
                        }
                    }
                }
                is IntegrationSetupState.Provisioning -> {
                    SettingUpScreen(
                        state = (state as IntegrationSetupState.Provisioning).settingUpState,
                        onCancel = { navController.popBackStack() }
                    )
                }
                is IntegrationSetupState.Failed -> {
                    SettingUpScreen(
                        state = SettingUpState(
                            variant = SettingUpVariant.RateLimited(
                                (state as IntegrationSetupState.Failed).message
                            )
                        ),
                        onCancel = { navController.popBackStack() }
                    )
                }
                is IntegrationSetupState.RateLimited -> {
                    SettingUpScreen(
                        state = SettingUpState(
                            variant = SettingUpVariant.RateLimited(
                                (state as IntegrationSetupState.RateLimited).retryDate
                            )
                        ),
                        onCancel = { navController.popBackStack() }
                    )
                }
                is IntegrationSetupState.Idle -> {
                    // Waiting for setup to begin
                }
            }
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

        composable(Routes.NOTIFICATION_SETUP) {
            val viewModel: NotificationSetupViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current
            val lifecycle = lifecycleOwner.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshPermission()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            NotificationSetupScreen(
                state = state,
                onGrantAccess = {
                    val intent = Intent(
                        Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
                    )
                    navController.context.startActivity(intent)
                },
                onRetentionChanged = viewModel::setRetentionDays,
                onAllowActionsChanged = viewModel::setAllowActions,
                onEnable = {
                    if (viewModel.enable()) {
                        navController.navigate(
                            Routes.integrationEnabled(NotificationSetupViewModel.INTEGRATION_ID)
                        ) {
                            popUpTo(Routes.ADD_INTEGRATION) { inclusive = true }
                        }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.OUTREACH_SETUP) {
            val viewModel: OutreachSetupViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current
            val lifecycle = lifecycleOwner.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshPermission()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            OutreachSetupScreen(
                state = state,
                onDndToggled = viewModel::setDndToggled,
                onGrantDnd = {
                    val intent = Intent(
                        Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                    )
                    navController.context.startActivity(intent)
                },
                onEnable = {
                    viewModel.enable()
                    navController.navigate(
                        Routes.integrationEnabled(OutreachSetupViewModel.INTEGRATION_ID)
                    ) {
                        popUpTo(Routes.ADD_INTEGRATION) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() }
            )
        }

        composable(Routes.USAGE_SETUP) {
            val viewModel: UsageSetupViewModel = koinViewModel()
            val state by viewModel.state.collectAsState()
            val lifecycleOwner = LocalLifecycleOwner.current
            val lifecycle = lifecycleOwner.lifecycle
            DisposableEffect(lifecycle) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        viewModel.refreshPermission()
                    }
                }
                lifecycle.addObserver(observer)
                onDispose { lifecycle.removeObserver(observer) }
            }
            UsageSetupScreen(
                state = state,
                onGrantAccess = {
                    val intent = Intent(
                        Settings.ACTION_USAGE_ACCESS_SETTINGS
                    )
                    navController.context.startActivity(intent)
                },
                onEnable = {
                    if (viewModel.enable()) {
                        navController.navigate(
                            Routes.integrationEnabled(UsageSetupViewModel.INTEGRATION_ID)
                        ) {
                            popUpTo(Routes.ADD_INTEGRATION) { inclusive = true }
                        }
                    }
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
                        NotificationSetupViewModel.INTEGRATION_ID -> "Notifications"
                        OutreachSetupViewModel.INTEGRATION_ID -> "Outreach"
                        UsageSetupViewModel.INTEGRATION_ID -> "Usage Stats"
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

        composable(Routes.AUTH_APPROVAL) {
            val viewModel: AuthorizationApprovalViewModel = koinViewModel()
            val requests by viewModel.pendingRequests.collectAsState()
            AuthorizationApprovalScreen(
                pendingRequests = requests.map { req ->
                    AuthorizationApprovalItem(
                        displayCode = req.displayCode,
                        integration = req.integration
                    )
                },
                onApprove = viewModel::approve,
                onDeny = viewModel::deny
            )
        }

        composable(
            route = Routes.AUDIT_DETAIL,
            arguments = listOf(navArgument("entryId") { type = NavType.LongType })
        ) { backStackEntry ->
            val entryId = backStackEntry.arguments?.getLong("entryId") ?: return@composable
            val auditDao: com.rousecontext.notifications.audit.AuditDao = org.koin.compose
                .koinInject()
            var detailState by remember {
                mutableStateOf(AuditDetailState(isLoading = true))
            }
            LaunchedEffect(entryId) {
                val entry = auditDao.getById(entryId)
                detailState = if (entry != null) {
                    AuditDetailState(
                        toolName = entry.toolName,
                        provider = entry.provider,
                        timestampMillis = entry.timestampMillis,
                        durationMs = entry.durationMillis,
                        argumentsJson = entry.argumentsJson,
                        resultJson = entry.resultJson,
                        isLoading = false
                    )
                } else {
                    AuditDetailState(isLoading = false)
                }
            }
            AuditDetailScreen(
                state = detailState,
                onBack = { navController.popBackStack() }
            )
        }
    }
}

/**
 * Health Connect permissions requested during setup.
 * Derived from RecordTypeRegistry so every supported record type is included.
 */
private val HEALTH_CONNECT_PERMISSIONS: Set<String> =
    com.rousecontext.mcp.health.RecordTypeRegistry.allPermissions
