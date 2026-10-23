package com.rousecontext.app.ui.navigation.destinations

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rousecontext.app.R
import com.rousecontext.app.ui.navigation.ConfigureNavBar
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.HealthConnectSetupContent
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.integrations.health.RecordTypeRegistry
import org.koin.androidx.compose.koinViewModel

/**
 * Health Connect permissions requested during setup.
 * Derived from RecordTypeRegistry so every supported record type is included.
 */
private val HEALTH_CONNECT_PERMISSIONS: Set<String> =
    RecordTypeRegistry.allPermissions

@Suppress("LongMethod", "CyclomaticComplexMethod")
fun NavGraphBuilder.healthConnectSetupDestination(navController: NavController) {
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
                stringResource(R.string.destination_title_health_connect_settings)
            } else {
                stringResource(R.string.destination_title_health_connect_setup)
            },
            showBackButton = true,
            onBackPressed = { navController.popBackStack() }
        )
        val viewModel: HealthConnectSetupViewModel =
            koinViewModel()
        LaunchedEffect(Unit) {
            viewModel.refreshPermissions()
        }
        // In SETTINGS mode the user may toggle individual permissions
        // inside Health Connect and return; re-query so the
        // historical-access card and per-record-type indicators
        // reflect the latest state (#94, #99).
        // Availability is re-read on resume too, so the screen recovers once
        // the user comes back from installing Health Connect (#803).
        val context = LocalContext.current
        var healthConnectAvailable by remember {
            mutableStateOf(isHealthConnectAvailable(context))
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycle = lifecycleOwner.lifecycle
        DisposableEffect(lifecycle) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        healthConnectAvailable = isHealthConnectAvailable(context)
                        viewModel.refreshPermissions()
                    }
                }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        val historicalGranted by viewModel.historicalAccessGranted
            .collectAsState()
        val grantedRecordTypes by viewModel.grantedRecordTypes
            .collectAsState()
        val requestPermissions =
            rememberLauncherForActivityResult(
                contract = PermissionController
                    .createRequestPermissionResultContract()
            ) { granted ->
                viewModel.onPermissionsResult(granted)
                // SETUP deliberately stays on this screen: the
                // "Grant historical access" button only becomes
                // usable once the base permission has landed, so
                // advancing here would make it unreachable (#537).
                // The user leaves via the Continue button below.
                if (mode == SetupMode.SETTINGS) {
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
            healthConnectAvailable = healthConnectAvailable,
            onInstallHealthConnect = { openHealthConnectInstall(context) },
            onGrantAccess = {
                launchHealthConnectOrInstall(context) {
                    if (mode == SetupMode.SETTINGS) {
                        // #94: no public deep-link to per-app
                        // permissions (the direct action is
                        // signature-guarded by
                        // GRANT_RUNTIME_PERMISSIONS). Falling back to
                        // HC home — user taps "App permissions" to
                        // reach the per-app view. The SDK constant
                        // picks the platform action on 14+ and the
                        // Play app's action on 13 and lower.
                        context.startActivity(
                            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } else {
                        requestPermissions.launch(HEALTH_CONNECT_PERMISSIONS)
                    }
                }
            },
            onContinue = {
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
                launchHealthConnectOrInstall(context) {
                    requestHistoricalPermission.launch(
                        setOf(HealthConnectSetupViewModel.HISTORY_PERMISSION)
                    )
                }
            },
            grantedRecordTypes = grantedRecordTypes
        )
    }
}
