package com.rousecontext.app.ui.navigation.destinations

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
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.androidx.viewmodel.dsl.viewModel
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.robolectric.RobolectricTestRunner
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

    @After
    fun tearDown() {
        stopKoin()
    }

    private val currentRoute: String?
        get() = composeRule.runOnIdle {
            navController.currentBackStackEntry?.destination?.route
        }

    private fun start(mode: SetupMode, grantResult: Set<String>) {
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
    }
}
