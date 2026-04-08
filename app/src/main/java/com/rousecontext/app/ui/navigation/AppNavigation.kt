package com.rousecontext.app.ui.navigation

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import com.rousecontext.app.buildMcpUrl
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.screens.AddIntegrationPickerContent
import com.rousecontext.app.ui.screens.AuditDetailContent
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditHistoryContent
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalScreen
import com.rousecontext.app.ui.screens.DeviceCodeApprovalScreen
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
import com.rousecontext.app.ui.screens.UsageSetupContent
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

private val TAB_INDEX = mapOf(
    Routes.HOME to 0,
    Routes.AUDIT to 1,
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
                                navController.navigate(Routes.AUDIT) {
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
                            navController.navigate(Routes.AUDIT)
                        },
                        onPendingAuthRequests = {
                            navController.navigate(Routes.AUTH_APPROVAL)
                        }
                    )
                }

                composable(
                    Routes.AUDIT,
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
                        title = "Audit History",
                        showBottomBar = true
                    )
                    val viewModel: AuditHistoryViewModel = koinViewModel()
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
                        onThemeModeChanged = viewModel::setThemeMode
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
                    AddIntegrationPickerContent(
                        integrations = integrations,
                        onSetUp = { id ->
                            when (id) {
                                HealthConnectSetupViewModel
                                    .HEALTH_INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.HEALTH_CONNECT_SETUP
                                    )
                                NotificationSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.NOTIFICATION_SETUP
                                    )
                                OutreachSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.OUTREACH_SETUP
                                    )
                                UsageSetupViewModel.INTEGRATION_ID ->
                                    navController.navigate(
                                        Routes.USAGE_SETUP
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
                                    navController.popBackStack()
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
                                    navController.popBackStack()
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
                                    navController.popBackStack()
                                }
                            )
                        }
                        is IntegrationSetupState.Idle -> {
                            // Waiting for setup to begin
                        }
                    }
                }

                composable(Routes.HEALTH_CONNECT_SETUP) {
                    ConfigureNavBar(
                        title = "Health Connect Setup",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: HealthConnectSetupViewModel =
                        koinViewModel()
                    val requestPermissions =
                        rememberLauncherForActivityResult(
                            contract = PermissionController
                                .createRequestPermissionResultContract()
                        ) { granted ->
                            val enabled =
                                viewModel.onPermissionsResult(granted)
                            if (enabled) {
                                navController.navigate(
                                    Routes.integrationEnabled(
                                        HealthConnectSetupViewModel
                                            .HEALTH_INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            }
                        }
                    HealthConnectSetupContent(
                        onGrantAccess = {
                            requestPermissions.launch(
                                HEALTH_CONNECT_PERMISSIONS
                            )
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }

                composable(Routes.NOTIFICATION_SETUP) {
                    ConfigureNavBar(
                        title = "Notification Access",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: NotificationSetupViewModel =
                        koinViewModel()
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
                            if (viewModel.enable()) {
                                navController.navigate(
                                    Routes.integrationEnabled(
                                        NotificationSetupViewModel
                                            .INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            }
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }

                composable(Routes.OUTREACH_SETUP) {
                    ConfigureNavBar(
                        title = "Outreach",
                        showBackButton = true,
                        onBackPressed = { navController.popBackStack() }
                    )
                    val viewModel: OutreachSetupViewModel =
                        koinViewModel()
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
                        onDndToggled = viewModel::setDndToggled,
                        onGrantDnd = {
                            val intent = Intent(
                                Settings
                                    .ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                            )
                            navController.context.startActivity(intent)
                        },
                        onEnable = {
                            viewModel.enable()
                            navController.navigate(
                                Routes.integrationEnabled(
                                    OutreachSetupViewModel.INTEGRATION_ID
                                )
                            ) {
                                popUpTo(Routes.ADD_INTEGRATION) {
                                    inclusive = true
                                }
                            }
                        },
                        onCancel = { navController.popBackStack() }
                    )
                }

                composable(Routes.USAGE_SETUP) {
                    ConfigureNavBar(
                        title = "Usage Stats",
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
                        onGrantAccess = {
                            val intent = Intent(
                                Settings.ACTION_USAGE_ACCESS_SETTINGS
                            )
                            navController.context.startActivity(intent)
                        },
                        onEnable = {
                            if (viewModel.enable()) {
                                navController.navigate(
                                    Routes.integrationEnabled(
                                        UsageSetupViewModel.INTEGRATION_ID
                                    )
                                ) {
                                    popUpTo(Routes.ADD_INTEGRATION) {
                                        inclusive = true
                                    }
                                }
                            }
                        },
                        onCancel = { navController.popBackStack() }
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
                    val certStore:
                        com.rousecontext.tunnel.CertificateStore =
                        org.koin.compose.koinInject()
                    val integrations:
                        List<com.rousecontext.api.McpIntegration> =
                        org.koin.compose.koinInject()
                    val integration =
                        integrations.find { it.id == integrationId }
                    val subdomain =
                        androidx.compose.runtime.produceState("") {
                            value =
                                certStore.getSubdomain() ?: "unknown"
                        }.value
                    val integrationSecret =
                        androidx.compose.runtime
                            .produceState<String?>(null) {
                                value = certStore
                                    .getSecretForIntegration(
                                        integrationId
                                    )
                            }.value
                    val baseDomain =
                        com.rousecontext.app.BuildConfig.RELAY_HOST
                            .removePrefix("relay.")
                    val mcpUrl = if (
                        subdomain.isNotEmpty() &&
                        integrationSecret != null
                    ) {
                        buildMcpUrl(
                            integrationSecret,
                            subdomain,
                            baseDomain
                        )
                    } else {
                        ""
                    }
                    val integrationName = integration?.displayName
                        ?: integrationId
                    ConfigureNavBar(
                        title = "$integrationName Ready"
                    )
                    IntegrationEnabledContent(
                        state = IntegrationEnabledState(
                            integrationName = integrationName,
                            url = mcpUrl
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
                    arguments = listOf(
                        navArgument("integrationId") {
                            type = NavType.StringType
                        }
                    )
                ) {
                    ConfigureNavBar(
                        title = "Approve Connection",
                        showTopBar = false
                    )
                    val viewModel: DeviceCodeApprovalViewModel =
                        koinViewModel()
                    val state by viewModel.state.collectAsState()
                    DeviceCodeApprovalScreen(
                        state = state,
                        onCodeChanged = viewModel::onCodeChanged,
                        onApprove = viewModel::approve,
                        onDeny = viewModel::deny
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
                                argumentsJson = entry.argumentsJson,
                                resultJson = entry.resultJson,
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
