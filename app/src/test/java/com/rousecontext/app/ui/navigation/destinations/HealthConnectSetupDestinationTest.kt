package com.rousecontext.app.ui.navigation.destinations

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.HarnessFakeHealthConnectRepository
import com.rousecontext.app.ui.navigation.LocalNavBarController
import com.rousecontext.app.ui.navigation.NavBarControllerImpl
import com.rousecontext.app.ui.navigation.Routes
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.viewmodels.HealthConnectSetupViewModel
import com.rousecontext.integrations.health.HealthConnectRepository
import com.rousecontext.integrations.health.RecordTypeRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Navigation behaviour of the Health Connect setup destination (#537).
 *
 * The base-permission grant used to navigate straight to the
 * integration-setup screen, which made the "Grant historical access" button —
 * enabled only *after* that grant — impossible to reach. The screen now stays
 * put after the grant and advances only when the user presses Continue.
 *
 * The permission dialog is stood in for by an [ActivityResultRegistry] that
 * dispatches a canned result the moment the launcher fires, so the production
 * result callback runs exactly as it does on device.
 *
 * Health Connect availability (#803) is the real SDK check against
 * Robolectric's package manager: by default the provider app is installed, and
 * tests that need it missing say so. When nothing handles the permission
 * intent the registry throws [ActivityNotFoundException], exactly as
 * ComponentActivity's registry does on a device without Health Connect.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class HealthConnectSetupDestinationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val repository = HarnessFakeHealthConnectRepository()
    private val stateStore = FakeIntegrationStateStore()
    private lateinit var navController: NavHostController
    private val launchedPermissionRequests = mutableListOf<Any?>()
    private val application: Application = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        stopKoin()
    }

    private val currentRoute: String?
        get() = composeRule.runOnIdle {
            navController.currentBackStackEntry?.destination?.route
        }

    private fun start(
        mode: SetupMode,
        grantResult: Set<String> = emptySet(),
        healthConnectInstalled: Boolean = true,
        permissionActivityResolves: Boolean = healthConnectInstalled
    ) {
        if (healthConnectInstalled) installHealthConnect()
        startKoin {
            modules(
                module {
                    viewModel { HealthConnectSetupViewModel(stateStore, repository) }
                    single<HealthConnectRepository> { repository }
                }
            )
        }
        val registryOwner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(
                    requestCode: Int,
                    contract: ActivityResultContract<I, O>,
                    input: I,
                    options: ActivityOptionsCompat?
                ) {
                    if (!permissionActivityResolves) {
                        throw ActivityNotFoundException(
                            "No Activity found to handle Intent " +
                                "{ act=androidx.health.ACTION_REQUEST_PERMISSIONS }"
                        )
                    }
                    launchedPermissionRequests += input
                    // The user "grants" in the system dialog: the record types
                    // land in Health Connect and the result comes straight back.
                    repository.grantedPermissions =
                        RecordTypeRegistry.namesForPermissions(grantResult).toMutableSet()
                    @Suppress("UNCHECKED_CAST")
                    dispatchResult(requestCode, grantResult as O)
                }
            }
        }
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                CompositionLocalProvider(
                    LocalNavBarController provides NavBarControllerImpl(),
                    LocalActivityResultRegistryOwner provides registryOwner
                ) {
                    navController = rememberNavController()
                    NavHost(
                        navController = navController,
                        startDestination = Routes.healthConnectSetup(mode)
                    ) {
                        healthConnectSetupDestination(navController)
                        composable(Routes.INTEGRATION_SETUP) { Text("integration setup") }
                        composable(Routes.INTEGRATION_MANAGE) { Text("integration manage") }
                        composable(Routes.ADD_INTEGRATION) { Text("add integration") }
                        composable(Routes.HOME) { Text("home") }
                    }
                }
            }
        }
    }

    private fun clickPrimary(label: String) {
        composeRule.onNodeWithText(label).performScrollTo().performClick()
        composeRule.waitForIdle()
    }

    @Test
    fun `granting the base permission in setup mode stays on the setup screen`() {
        start(SetupMode.SETUP, grantResult = setOf(STEPS_PERMISSION))

        clickPrimary("Grant All Health Access")

        assertEquals(Routes.HEALTH_CONNECT_SETUP, currentRoute)
        composeRule.onNodeWithText("Continue").performScrollTo().assertExists()
    }

    @Test
    fun `continue after the base grant advances to integration setup`() {
        start(SetupMode.SETUP, grantResult = setOf(STEPS_PERMISSION))

        clickPrimary("Grant All Health Access")
        clickPrimary("Continue")

        assertEquals(Routes.INTEGRATION_SETUP, currentRoute)
    }

    @Test
    fun `denying every permission in setup mode leaves the grant action in place`() {
        start(SetupMode.SETUP, grantResult = emptySet())

        clickPrimary("Grant All Health Access")

        assertEquals(Routes.HEALTH_CONNECT_SETUP, currentRoute)
        composeRule.onNodeWithText("Grant All Health Access").performScrollTo().assertExists()
    }

    @Test
    fun `settings mode keeps sending the user to health connect without navigating`() {
        repository.grantedPermissions = mutableSetOf("Steps")
        start(SetupMode.SETTINGS, grantResult = setOf(STEPS_PERMISSION))

        clickPrimary("Manage in Health Connect")

        assertEquals(Routes.HEALTH_CONNECT_SETUP, currentRoute)
    }

    @Test
    fun `granting without health connect installed opens the play listing instead of crashing`() {
        start(SetupMode.SETUP, healthConnectInstalled = false)

        clickPrimary("Install Health Connect")

        assertPlayListingOpened()
        assertEquals(emptyList<Any?>(), launchedPermissionRequests)
        assertEquals(Routes.HEALTH_CONNECT_SETUP, currentRoute)
    }

    @Test
    fun `setup without health connect installed explains why and offers the install`() {
        start(SetupMode.SETUP, healthConnectInstalled = false)

        composeRule.onNodeWithText("Health Connect isn't installed").assertExists()
        composeRule.onNodeWithText("Grant All Health Access").assertDoesNotExist()
    }

    @Test
    fun `a missing permission handler falls back to the play listing instead of crashing`() {
        // The SDK says Health Connect is there, but nothing resolves the
        // permission intent (the #803 crash shape on a stale provider).
        start(
            SetupMode.SETUP,
            grantResult = setOf(STEPS_PERMISSION),
            permissionActivityResolves = false
        )

        clickPrimary("Grant All Health Access")

        assertPlayListingOpened()
        assertEquals(Routes.HEALTH_CONNECT_SETUP, currentRoute)
    }

    @Test
    fun `granting with health connect installed launches the permission request`() {
        start(SetupMode.SETUP, grantResult = setOf(STEPS_PERMISSION))

        clickPrimary("Grant All Health Access")

        assertEquals(listOf<Any?>(RecordTypeRegistry.allPermissions), launchedPermissionRequests)
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun `settings mode without health connect installed opens the play listing`() {
        start(SetupMode.SETTINGS, healthConnectInstalled = false)

        clickPrimary("Install Health Connect")

        assertPlayListingOpened()
    }

    @Test
    fun `settings mode opens the health connect settings screen for this platform`() {
        repository.grantedPermissions = mutableSetOf("Steps")
        start(SetupMode.SETTINGS)

        clickPrimary("Manage in Health Connect")

        assertEquals(
            HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS,
            shadowOf(application).nextStartedActivity?.action
        )
    }

    private fun assertPlayListingOpened() {
        val started = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals(
            "market://details?id=com.google.android.apps.healthdata" +
                "&url=healthconnect%3A%2F%2Fonboarding",
            started?.dataString
        )
    }

    /**
     * Makes `HealthConnectClient.getSdkStatus` report `SDK_AVAILABLE` on API 33:
     * the provider package at a supported version, exposing the bind service.
     */
    private fun installHealthConnect() {
        val pm = shadowOf(application.packageManager)
        val info = PackageInfo().apply {
            packageName = HEALTH_CONNECT_PACKAGE
            longVersionCode = HEALTH_CONNECT_MIN_VERSION_CODE
            applicationInfo = ApplicationInfo().apply {
                packageName = HEALTH_CONNECT_PACKAGE
                enabled = true
            }
        }
        pm.installPackage(info)
        val service = ComponentName(HEALTH_CONNECT_PACKAGE, "HealthDataService")
        pm.addServiceIfNotPresent(service)
        pm.addIntentFilterForService(
            service,
            IntentFilter("androidx.health.ACTION_BIND_HEALTH_DATA_SERVICE")
        )
    }

    private class FakeIntegrationStateStore : IntegrationStateStore {
        private val enabled = mutableMapOf<String, Boolean>()
        override suspend fun isUserEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override suspend fun setUserEnabled(integrationId: String, enabled: Boolean) {
            this.enabled[integrationId] = enabled
        }
        override fun observeUserEnabled(integrationId: String) =
            MutableStateFlow(enabled[integrationId] == true)
        override suspend fun wasEverEnabled(integrationId: String): Boolean =
            enabled[integrationId] == true
        override fun observeEverEnabled(integrationId: String): Flow<Boolean> =
            MutableStateFlow(enabled[integrationId] == true)
        override fun observeChanges(): Flow<Unit> = MutableStateFlow(0).map { }
    }

    private companion object {
        const val STEPS_PERMISSION = "android.permission.health.READ_STEPS"

        // HealthConnectClient's DEFAULT_PROVIDER_* constants are internal.
        const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
        const val HEALTH_CONNECT_MIN_VERSION_CODE = 68623L
    }
}
