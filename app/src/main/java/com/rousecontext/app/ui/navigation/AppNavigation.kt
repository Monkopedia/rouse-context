package com.rousecontext.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.screens.AddIntegrationPickerContent
import com.rousecontext.app.ui.screens.AllClientsContent
import com.rousecontext.app.ui.screens.AuditDetailContent
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditHistoryContent
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalScreen
import com.rousecontext.app.ui.screens.HealthConnectSetupContent
import com.rousecontext.app.ui.screens.HomeDashboardContent
import com.rousecontext.app.ui.screens.IntegrationEnabledContent
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationManageContent
import com.rousecontext.app.ui.screens.NotificationSetupContent
import com.rousecontext.app.ui.screens.OutreachSetupContent
import com.rousecontext.app.ui.screens.SettingUpContent
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.SettingsContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.screens.UsageSetupContent
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.viewmodels.AddIntegrationViewModel
import com.rousecontext.app.ui.viewmodels.AuditHistoryViewModel
import com.rousecontext.app.ui.viewmodels.AuthorizationApprovalViewModel
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
import com.rousecontext.tunnel.CertProvisioningFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.koin.androidx.compose.koinViewModel

object Routes {
    const val ONBOARDING = "onboarding"
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

private val TAB_INDEX = mapOf(
    Routes.HOME to 0,
    Routes.AUDIT to 1,
    Routes.AUDIT_BASE to 1,
    Routes.SETTINGS to 2
)

/**
 * Determines the slide direction for tab transitions based on
 * the relative index of the source and destination tabs.
 */
private fun tabSlideDirection(initialRoute: String?, targetRoute: String?): Int {
    val from = TAB_INDEX[initialRoute] ?: return 1
    val to = TAB_INDEX[targetRoute] ?: return 1
    return if (to > from) 1 else -1
}

private val ONBOARDING_ROUTES = setOf(Routes.ONBOARDING)

@Suppress("CyclomaticComplexity", "LongMethod")
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
            topBar = {
                if (!isOnboarding && controller.showTopBar) {
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
            },
            bottomBar = {
                Column(modifier = Modifier.animateContentSize()) {
                    AnimatedVisibility(
                        visible = !isOnboarding && controller.showBottomBar,
                        enter = slideInVertically(
                            initialOffsetY = { it }
                        ) + fadeIn(),
                        exit = slideOutVertically(
                            targetOffsetY = { it }
                        ) + fadeOut()
                    ) {
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
                }
            }
        ) { padding ->
            NavHost(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(padding)
            ) {
                composable(Routes.ONBOARDING) {
                    ConfigureNavBar(
                        title = "",
                        showTopBar = false,
                        showBottomBar = false
                    )
                    val onboardingViewModel: OnboardingViewModel =
                        koinViewModel()
                    val state by onboardingViewModel.state.collectAsState()

                    LaunchedEffect(state) {
                        if (state is OnboardingState.Onboarded) {
                            navController.navigate(Routes.HOME) {
                                popUpTo(Routes.HOME) { inclusive = true }
                            }
                        }
                    }

                    when (state) {
                        is OnboardingState.Checking,
                        is OnboardingState.NotOnboarded -> {
                            WelcomeScreen(
                                onGetStarted = {
                                    onboardingViewModel.startOnboarding()
                                }
                            )
                        }
                        is OnboardingState.Onboarded,
                        is OnboardingState.RateLimited,
                        is OnboardingState.Failed -> {
                            // Non-blocking: LaunchedEffect above
                            // navigates to Home immediately.
                            // Show nothing while navigation is in flight.
                        }
                    }
                }

                // Tab routes with horizontal slide transitions
                composable(
                    Routes.HOME,
                    enterTransition = {
                        val fromRoute = initialState.destination.route
                        if (fromRoute != null && fromRoute in TAB_INDEX) {
                            val dir = tabSlideDirection(fromRoute, targetState.destination.route)
                            slideInHorizontally(
                                initialOffsetX = { dir * (it / 4) }
                            ) + fadeIn()
                        } else {
                            fadeIn()
                        }
                    },
                    exitTransition = {
                        val toRoute = targetState.destination.route
                        if (toRoute != null && toRoute in TAB_INDEX) {
                            val dir = tabSlideDirection(
                                initialState.destination.route,
                                toRoute
                            )
                            slideOutHorizontally(
                                targetOffsetX = { -dir * (it / 4) }
                            ) + fadeOut()
                        } else {
                            fadeOut()
                        }
                    }
                ) {
                    ConfigureNavBar(
                        title = "Rouse Context",
                        showBottomBar = true
                    )
                    val viewModel: MainDashboardViewModel = koinViewModel()
                    val state by viewModel.state.collectAsState()

                    LaunchedEffect(
                        navController.currentBackStackEntry
                            ?.lifecycle?.currentState
                    ) {
                        viewModel.refresh()
                    }

                    HomeDashboardContent(
                        state = state,
                        onAddIntegration = {
                            navController.navigate(Routes.ADD_INTEGRATION)
                        },
                        onIntegrationClick = { id ->
                            navController.navigate(
                                Routes.integrationManage(id)
                            )
                        },
                        onViewAllActivity = {
                            navController.navigate(Routes.AUDIT_BASE)
                        }
                    )
                }

                composable(
                    Routes.AUDIT,
                    arguments = listOf(
                        navArgument("provider") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    ),
                    enterTransition = {
                        val dir = tabSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        slideInHorizontally(
                            initialOffsetX = { dir * (it / 4) }
                        ) + fadeIn()
                    },
                    exitTransition = {
                        val dir = tabSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        slideOutHorizontally(
                            targetOffsetX = { -dir * (it / 4) }
                        ) + fadeOut()
                    }
                ) { backStackEntry ->
                    ConfigureNavBar(
                        title = "Audit History",
                        showBottomBar = true
                    )
                    val viewModel: AuditHistoryViewModel = koinViewModel()
                    val providerArg = backStackEntry.arguments
                        ?.getString("provider")
                    LaunchedEffect(providerArg) {
                        if (providerArg != null) {
                            viewModel.setProviderFilter(providerArg)
                        }
                    }
                    val state by viewModel.state.collectAsState()
                    AuditHistoryContent(
                        state = state,
                        onProviderFilterChanged =
                        viewModel::setProviderFilter,
                        onDateFilterChanged = viewModel::setDateFilter,
                        onClearHistory = viewModel::clearHistory,
                        onEntryClick = { entryId ->
                            navController.navigate(
                                Routes.auditDetail(entryId)
                            )
                        }
                    )
                }

                composable(
                    Routes.SETTINGS,
                    enterTransition = {
                        val dir = tabSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        slideInHorizontally(
                            initialOffsetX = { dir * (it / 4) }
                        ) + fadeIn()
                    },
                    exitTransition = {
                        val dir = tabSlideDirection(
                            initialState.destination.route,
                            targetState.destination.route
                        )
                        slideOutHorizontally(
                            targetOffsetX = { -dir * (it / 4) }
                        ) + fadeOut()
                    }
                ) {
                    ConfigureNavBar(
                        title = "Settings",
                        showBottomBar = true
                    )
                    val viewModel: SettingsViewModel = koinViewModel()
                    val state by viewModel.state.collectAsState()
                    SettingsContent(
                        state = state,
                        onIdleTimeoutChanged = viewModel::setIdleTimeout,
                        onPostSessionModeChanged =
                        viewModel::setPostSessionMode,
                        onThemeModeChanged = viewModel::setThemeMode,
                        onSecurityCheckIntervalChanged =
                        viewModel::setSecurityCheckInterval,
                        onGenerateNewAddress = viewModel::rotateSecret,
                        onAcknowledgeAlert = viewModel::acknowledgeAlert
                    )
                }

                composable(Routes.ADD_INTEGRATION) {
                    ConfigureNavBar(
                        title = "Add Integration",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: AddIntegrationViewModel = koinViewModel()
                    val integrations by viewModel.pickerIntegrations
                        .collectAsState()
                    val certProvisioningFlow: CertProvisioningFlow =
                        org.koin.compose.koinInject()
                    val appScope: kotlinx.coroutines.CoroutineScope =
                        org.koin.compose.koinInject(
                            org.koin.core.qualifier.named("appScope")
                        )
                    AddIntegrationPickerContent(
                        integrations = integrations,
                        onSetUp = { id ->
                            // Kick off cert provisioning in the background while
                            // user configures the integration. Survives navigation
                            // since it uses the app-scoped coroutine scope.
                            appScope.launch {
                                try {
                                    val token = com.google.firebase.auth
                                        .FirebaseAuth.getInstance()
                                        .currentUser?.getIdToken(false)
                                        ?.await()?.token
                                    if (token != null) {
                                        certProvisioningFlow.execute(token)
                                    }
                                } catch (_: Exception) {
                                    // Best-effort; integrationSetup will retry
                                }
                            }
                            when (id) {
                                HealthConnectSetupViewModel
                                    .HEALTH_INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.healthConnectSetup(
                                            SetupMode.SETUP
                                        )
                                    )
                                NotificationSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.notificationSetup(
                                            SetupMode.SETUP
                                        )
                                    )
                                OutreachSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.outreachSetup(
                                            SetupMode.SETUP
                                        )
                                    )
                                UsageSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.usageSetup(
                                            SetupMode.SETUP
                                        )
                                    )
                                else ->
                                    navController.navigate(
                                        Routes.integrationSetup(id)
                                    )
                            }
                        }
                    )
                }

                composable(
                    route = Routes.INTEGRATION_MANAGE,
                    arguments = listOf(
                        navArgument("integrationId") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val integrationId = backStackEntry.arguments
                        ?.getString("integrationId")
                        ?: return@composable
                    val viewModel: IntegrationManageViewModel =
                        koinViewModel()
                    LaunchedEffect(integrationId) {
                        viewModel.loadIntegration(integrationId)
                    }
                    val state by viewModel.state.collectAsState()
                    IntegrationManageContent(
                        state = state,
                        onAddClient = {
                            navController.navigate(
                                Routes.integrationEnabled(integrationId)
                            )
                        },
                        onViewAllClients = {
                            navController.navigate(
                                Routes.allClients(integrationId)
                            )
                        },
                        onEntryClick = { entryId ->
                            navController.navigate(
                                Routes.auditDetail(entryId)
                            )
                        },
                        onViewAllActivity = {
                            navController.navigate(
                                Routes.audit(provider = integrationId)
                            ) {
                                popUpTo(Routes.HOME)
                            }
                        },
                        onSettings = {
                            when (integrationId) {
                                HealthConnectSetupViewModel
                                    .HEALTH_INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.healthConnectSetup(
                                            SetupMode.SETTINGS
                                        )
                                    )
                                NotificationSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.notificationSetup(
                                            SetupMode.SETTINGS
                                        )
                                    )
                                OutreachSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.outreachSetup(
                                            SetupMode.SETTINGS
                                        )
                                    )
                                UsageSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.usageSetup(
                                            SetupMode.SETTINGS
                                        )
                                    )
                            }
                        },
                        onDisable = {
                            viewModel.disable()
                            navController.popBackStack()
                        },
                        onRevokeClient = { clientName ->
                            viewModel.revokeClient(clientName)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.ALL_CLIENTS,
                    arguments = listOf(
                        navArgument("integrationId") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val integrationId = backStackEntry.arguments
                        ?.getString("integrationId")
                        ?: return@composable
                    val viewModel: IntegrationManageViewModel =
                        koinViewModel()
                    LaunchedEffect(integrationId) {
                        viewModel.loadIntegration(integrationId)
                    }
                    val state by viewModel.state.collectAsState()
                    AllClientsContent(
                        integrationName = state.integrationName,
                        clients = state.authorizedClients,
                        onRevokeClient = { clientName ->
                            viewModel.revokeClient(clientName)
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable(
                    route = Routes.INTEGRATION_SETUP,
                    arguments = listOf(
                        navArgument("integrationId") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    ConfigureNavBar(
                        title = "Setting Up",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val integrationId = backStackEntry.arguments
                        ?.getString("integrationId")
                        ?: return@composable
                    val viewModel: IntegrationSetupViewModel =
                        koinViewModel()
                    LaunchedEffect(integrationId) {
                        viewModel.startSetup(integrationId)
                    }
                    val state by viewModel.state.collectAsState()
                    when (state) {
                        is IntegrationSetupState.Complete -> {
                            LaunchedEffect(Unit) {
                                navController.navigate(
                                    Routes.integrationEnabled(integrationId)
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            }
                        }
                        is IntegrationSetupState.Provisioning -> {
                            SettingUpContent(
                                state = (
                                    state as
                                        IntegrationSetupState.Provisioning
                                    ).settingUpState,
                                onCancel = {
                                    navController.navigate(
                                        Routes.integrationManage(
                                            integrationId
                                        )
                                    ) {
                                        popUpTo(Routes.HOME)
                                    }
                                }
                            )
                        }
                        is IntegrationSetupState.Failed -> {
                            SettingUpContent(
                                state = SettingUpState(
                                    variant = SettingUpVariant.RateLimited(
                                        (
                                            state as
                                                IntegrationSetupState.Failed
                                            ).message
                                    )
                                ),
                                onCancel = {
                                    navController.navigate(
                                        Routes.integrationManage(
                                            integrationId
                                        )
                                    ) {
                                        popUpTo(Routes.HOME)
                                    }
                                }
                            )
                        }
                        is IntegrationSetupState.RateLimited -> {
                            SettingUpContent(
                                state = SettingUpState(
                                    variant = SettingUpVariant.RateLimited(
                                        (
                                            state as
                                                IntegrationSetupState
                                                    .RateLimited
                                            ).retryDate
                                    )
                                ),
                                onCancel = {
                                    navController.navigate(
                                        Routes.integrationManage(
                                            integrationId
                                        )
                                    ) {
                                        popUpTo(Routes.HOME)
                                    }
                                }
                            )
                        }
                        is IntegrationSetupState.Idle -> {
                            // Waiting for setup to begin
                        }
                    }
                }

                composable(
                    route = Routes.HEALTH_CONNECT_SETUP,
                    arguments = listOf(
                        navArgument("mode") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val modeArg = backStackEntry.arguments
                        ?.getString("mode")
                    val mode = if (modeArg == SetupMode.SETTINGS.name) {
                        SetupMode.SETTINGS
                    } else {
                        SetupMode.SETUP
                    }
                    ConfigureNavBar(
                        title = if (mode == SetupMode.SETTINGS) {
                            "Health Connect Settings"
                        } else {
                            "Health Connect Setup"
                        },
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: HealthConnectSetupViewModel =
                        koinViewModel()
                    LaunchedEffect(Unit) {
                        viewModel.refreshHistoricalAccess()
                    }
                    val historicalGranted by viewModel.historicalAccessGranted
                        .collectAsState()
                    val requestPermissions =
                        rememberLauncherForActivityResult(
                            contract = PermissionController
                                .createRequestPermissionResultContract()
                        ) { granted ->
                            val enabled =
                                viewModel.onPermissionsResult(granted)
                            if (enabled && mode == SetupMode.SETUP) {
                                navController.navigate(
                                    Routes.integrationSetup(
                                        HealthConnectSetupViewModel
                                            .HEALTH_INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            } else if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            }
                        }
                    val requestHistoricalPermission =
                        rememberLauncherForActivityResult(
                            contract = PermissionController
                                .createRequestPermissionResultContract()
                        ) { granted ->
                            viewModel.onHistoricalPermissionResult(granted)
                        }
                    HealthConnectSetupContent(
                        mode = mode,
                        onGrantAccess = {
                            requestPermissions.launch(
                                HEALTH_CONNECT_PERMISSIONS
                            )
                        },
                        onCancel = {
                            if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(
                                    Routes.integrationManage(
                                        HealthConnectSetupViewModel
                                            .HEALTH_INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        },
                        historicalAccessGranted = historicalGranted,
                        onRequestHistoricalAccess = {
                            requestHistoricalPermission.launch(
                                setOf(
                                    HealthConnectSetupViewModel
                                        .HISTORY_PERMISSION
                                )
                            )
                        }
                    )
                }

                composable(
                    route = Routes.NOTIFICATION_SETUP,
                    arguments = listOf(
                        navArgument("mode") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val modeArg = backStackEntry.arguments
                        ?.getString("mode")
                    val mode = if (modeArg == SetupMode.SETTINGS.name) {
                        SetupMode.SETTINGS
                    } else {
                        SetupMode.SETUP
                    }
                    ConfigureNavBar(
                        title = if (mode == SetupMode.SETTINGS) {
                            "Notification Settings"
                        } else {
                            "Notification Access"
                        },
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: NotificationSetupViewModel =
                        koinViewModel()
                    LaunchedEffect(mode) {
                        viewModel.initForMode(mode)
                    }
                    val state by viewModel.state.collectAsState()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val lifecycle = lifecycleOwner.lifecycle
                    DisposableEffect(lifecycle) {
                        val observer =
                            LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    viewModel.refreshPermission()
                                }
                            }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    NotificationSetupContent(
                        state = state,
                        mode = mode,
                        onGrantAccess = {
                            val intent = Intent(
                                Settings
                                    .ACTION_NOTIFICATION_LISTENER_SETTINGS
                            )
                            navController.context.startActivity(intent)
                        },
                        onRetentionChanged = viewModel::setRetentionDays,
                        onAllowActionsChanged = viewModel::setAllowActions,
                        onEnable = {
                            if (mode == SetupMode.SETTINGS) {
                                viewModel.saveSettings()
                                navController.popBackStack()
                            } else {
                                if (viewModel.enable()) {
                                    navController.navigate(
                                        Routes.integrationSetup(
                                            NotificationSetupViewModel
                                                .INTEGRATION_ID
                                        )
                                    ) {
                                        popUpTo(Routes.ADD_INTEGRATION) {
                                            inclusive = true
                                        }
                                    }
                                }
                            }
                        },
                        onCancel = {
                            if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(
                                    Routes.integrationManage(
                                        NotificationSetupViewModel
                                            .INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        }
                    )
                }

                composable(
                    route = Routes.OUTREACH_SETUP,
                    arguments = listOf(
                        navArgument("mode") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val modeArg = backStackEntry.arguments
                        ?.getString("mode")
                    val mode = if (modeArg == SetupMode.SETTINGS.name) {
                        SetupMode.SETTINGS
                    } else {
                        SetupMode.SETUP
                    }
                    ConfigureNavBar(
                        title = if (mode == SetupMode.SETTINGS) {
                            "Outreach Settings"
                        } else {
                            "Outreach"
                        },
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: OutreachSetupViewModel =
                        koinViewModel()
                    LaunchedEffect(mode) {
                        viewModel.initForMode(mode)
                    }
                    val state by viewModel.state.collectAsState()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val lifecycle = lifecycleOwner.lifecycle
                    DisposableEffect(lifecycle) {
                        val observer =
                            LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    viewModel.refreshPermission()
                                }
                            }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    OutreachSetupContent(
                        state = state,
                        mode = mode,
                        onDndToggled = viewModel::setDndToggled,
                        onGrantDnd = {
                            val intent = Intent(
                                Settings
                                    .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                            )
                            navController.context.startActivity(intent)
                        },
                        onEnable = {
                            if (mode == SetupMode.SETTINGS) {
                                viewModel.saveSettings()
                                navController.popBackStack()
                            } else {
                                viewModel.enable()
                                navController.navigate(
                                    Routes.integrationSetup(
                                        OutreachSetupViewModel
                                            .INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            }
                        },
                        onCancel = {
                            if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(
                                    Routes.integrationManage(
                                        OutreachSetupViewModel
                                            .INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        }
                    )
                }

                composable(
                    route = Routes.USAGE_SETUP,
                    arguments = listOf(
                        navArgument("mode") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val modeArg = backStackEntry.arguments
                        ?.getString("mode")
                    val mode = if (modeArg == SetupMode.SETTINGS.name) {
                        SetupMode.SETTINGS
                    } else {
                        SetupMode.SETUP
                    }
                    ConfigureNavBar(
                        title = if (mode == SetupMode.SETTINGS) {
                            "Usage Stats Settings"
                        } else {
                            "Usage Stats"
                        },
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: UsageSetupViewModel = koinViewModel()
                    val state by viewModel.state.collectAsState()
                    val lifecycleOwner = LocalLifecycleOwner.current
                    val lifecycle = lifecycleOwner.lifecycle
                    DisposableEffect(lifecycle) {
                        val observer =
                            LifecycleEventObserver { _, event ->
                                if (event == Lifecycle.Event.ON_RESUME) {
                                    viewModel.refreshPermission()
                                }
                            }
                        lifecycle.addObserver(observer)
                        onDispose { lifecycle.removeObserver(observer) }
                    }
                    UsageSetupContent(
                        state = state,
                        mode = mode,
                        onGrantAccess = {
                            val intent = Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS
                            )
                            navController.context.startActivity(intent)
                        },
                        onEnable = {
                            if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            } else {
                                if (viewModel.enable()) {
                                    navController.navigate(
                                        Routes.integrationSetup(
                                            UsageSetupViewModel
                                                .INTEGRATION_ID
                                        )
                                    ) {
                                        popUpTo(Routes.ADD_INTEGRATION) {
                                            inclusive = true
                                        }
                                    }
                                }
                            }
                        },
                        onCancel = {
                            if (mode == SetupMode.SETTINGS) {
                                navController.popBackStack()
                            } else {
                                navController.navigate(
                                    Routes.integrationManage(
                                        UsageSetupViewModel
                                            .INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.HOME)
                                }
                            }
                        }
                    )
                }

                composable(
                    route = Routes.INTEGRATION_ENABLED,
                    arguments = listOf(
                        navArgument("integrationId") {
                            type = NavType.StringType
                        }
                    )
                ) { backStackEntry ->
                    val integrationId = backStackEntry.arguments
                        ?.getString("integrationId")
                        ?: return@composable
                    val urlProvider: com.rousecontext.app.McpUrlProvider =
                        org.koin.compose.koinInject()
                    val integrations:
                        List<com.rousecontext.api.McpIntegration> =
                        org.koin.compose.koinInject()
                    val integration =
                        integrations.find { it.id == integrationId }
                    val mcpUrl =
                        androidx.compose.runtime.produceState("") {
                            value = urlProvider.buildUrl(
                                integrationId
                            ) ?: ""
                        }.value
                    val integrationName = integration?.displayName
                        ?: integrationId
                    ConfigureNavBar(
                        title = "$integrationName Ready"
                    )

                    // Auto-navigate to approval when a client auth request arrives
                    val mcpSession: com.rousecontext.mcp.core.McpSession =
                        org.koin.compose.koinInject()
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        mcpSession.authorizationCodeManager.newRequestFlow
                            .collect {
                                navController.navigate(Routes.AUTH_APPROVAL)
                            }
                    }

                    IntegrationEnabledContent(
                        state = IntegrationEnabledState(
                            integrationName = integrationName,
                            url = mcpUrl
                        ),
                        onCancel = {
                            navController.navigate(
                                Routes.integrationManage(integrationId)
                            ) {
                                popUpTo(Routes.HOME)
                            }
                        }
                    )
                }

                composable(Routes.AUTH_APPROVAL) {
                    ConfigureNavBar(
                        title = "Approve Connection",
                        showTopBar = false
                    )
                    val viewModel: AuthorizationApprovalViewModel =
                        koinViewModel()
                    val requests by viewModel.pendingRequests
                        .collectAsState()

                    // Track the integration from the last approved request
                    // so we can navigate to its manage page.
                    val lastApprovedIntegration =
                        androidx.compose.runtime.remember {
                            androidx.compose.runtime.mutableStateOf<String?>(
                                null
                            )
                        }

                    // When no pending requests remain after approval,
                    // navigate to the manage page for that integration.
                    val hadRequests = androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    if (requests.isNotEmpty()) {
                        hadRequests.value = true
                    }
                    if (hadRequests.value && requests.isEmpty()) {
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val target =
                                lastApprovedIntegration.value
                            if (target != null) {
                                navController.navigate(
                                    Routes.integrationManage(target)
                                ) {
                                    popUpTo(Routes.HOME)
                                }
                            } else {
                                navController.popBackStack()
                            }
                        }
                    }

                    AuthorizationApprovalScreen(
                        pendingRequests = requests.map { req ->
                            AuthorizationApprovalItem(
                                displayCode = req.displayCode,
                                integration = req.integration
                            )
                        },
                        onApprove = { displayCode ->
                            // Capture the integration before approving
                            // (which removes the request from the list).
                            val req = requests.find {
                                it.displayCode == displayCode
                            }
                            if (req != null) {
                                lastApprovedIntegration.value =
                                    req.integration
                            }
                            viewModel.approve(displayCode)
                        },
                        onDeny = viewModel::deny
                    )
                }

                composable(
                    route = Routes.AUDIT_DETAIL,
                    arguments = listOf(
                        navArgument("entryId") {
                            type = NavType.LongType
                        }
                    )
                ) { backStackEntry ->
                    ConfigureNavBar(
                        title = "Audit Detail",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val entryId = backStackEntry.arguments
                        ?.getLong("entryId") ?: return@composable
                    val auditDao: com.rousecontext.notifications
                        .audit.AuditDao =
                        org.koin.compose.koinInject()
                    val fieldEncryptor: com.rousecontext.notifications
                        .capture.FieldEncryptor =
                        org.koin.compose.koinInject()
                    var detailState by remember {
                        mutableStateOf(
                            AuditDetailState(isLoading = true)
                        )
                    }
                    LaunchedEffect(entryId) {
                        val entry = auditDao.getById(entryId)
                        detailState = if (entry != null) {
                            AuditDetailState(
                                toolName = entry.toolName,
                                provider = entry.provider,
                                timestampMillis = entry.timestampMillis,
                                durationMs = entry.durationMillis,
                                argumentsJson = fieldEncryptor.decrypt(
                                    entry.argumentsJson
                                ) ?: entry.argumentsJson,
                                resultJson = fieldEncryptor.decrypt(
                                    entry.resultJson
                                ) ?: entry.resultJson,
                                isLoading = false
                            )
                        } else {
                            AuditDetailState(isLoading = false)
                        }
                    }
                    AuditDetailContent(
                        state = detailState
                    )
                }
            }
        }
    }
}

/**
 * Health Connect permissions requested during setup.
 * Derived from RecordTypeRegistry so every supported record type is included.
 */
private val HEALTH_CONNECT_PERMISSIONS: Set<String> =
    com.rousecontext.mcp.health.RecordTypeRegistry.allPermissions
