package com.rousecontext.app.ui.navigation.destinations

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * Platform action for Health Connect's home settings screen.
 * Handled by the unguarded HC controller TrampolineActivity (no signature
 * permission required). See #94 for context on why per-app deep-link isn't
 * possible — that action is alias-guarded by GRANT_RUNTIME_PERMISSIONS.
 */
private const val HEALTH_CONNECT_HOME_ACTION =
    "android.health.connect.action.HEALTH_HOME_SETTINGS"

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
        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycle = lifecycleOwner.lifecycle
        DisposableEffect(lifecycle) {
            val observer =
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
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
        val context = LocalContext.current
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
                if (mode == SetupMode.SETTINGS) {
                    // #94: no public deep-link to per-app
                    // permissions (the direct action is
                    // signature-guarded by
                    // GRANT_RUNTIME_PERMISSIONS). Falling back to
                    // HC home — user taps "App permissions" to
                    // reach the per-app view.
                    try {
                        val intent = Intent(
                            HEALTH_CONNECT_HOME_ACTION
                        )
                        intent.flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    } catch (_: ActivityNotFoundException) {
                        // HC not installed — no-op.
                    }
                } else {
                    requestPermissions.launch(
                        HEALTH_CONNECT_PERMISSIONS
                    )
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
                requestHistoricalPermission.launch(
                    setOf(
                        HealthConnectSetupViewModel
                            .HISTORY_PERMISSION
                    )
                )
            },
            grantedRecordTypes = grantedRecordTypes
        )
    }
}
