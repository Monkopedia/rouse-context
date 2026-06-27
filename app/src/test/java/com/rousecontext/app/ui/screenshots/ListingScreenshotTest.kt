package com.rousecontext.app.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.delivery.DistributorOption
import com.rousecontext.app.ui.screens.AuditDetailScreen
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditDetailUiState
import com.rousecontext.app.ui.screens.AuditHistoryEntry
import com.rousecontext.app.ui.screens.AuditHistoryGroup
import com.rousecontext.app.ui.screens.AuditHistoryScreen
import com.rousecontext.app.ui.screens.AuditHistoryState
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.BackgroundDeliveryRowState
import com.rousecontext.app.ui.screens.BackgroundDeliveryScreen
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.HealthConnectSetupScreen
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.screens.NotificationPreferencesScreen
import com.rousecontext.app.ui.screens.SettingsScreen
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the nine F-Droid store-listing screenshots straight into the
 * fastlane metadata folder, DARK theme, phone-sized (1200x2400 = w400dp xxhdpi).
 *
 * These mirror the published gallery (ScreenScreenshotTest /
 * BackgroundDeliveryScreenshotTest) but use the exact `N_name.png` filenames
 * F-Droid orders by, and write to `phoneScreenshots/` rather than the gallery
 * dir. Run with the roborazzi record task:
 *
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
 *   ./gradlew :app:recordRoborazziDebug \
 *   --tests "com.rousecontext.app.ui.screenshots.ListingScreenshotTest"
 * ```
 *
 * The Settings render exercises the CURRENT Connection cluster (#508/#509):
 * Idle timeout, Quick disconnect, Disable timeout, Ignore daily time limit,
 * and Background delivery.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class ListingScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) { content() }
        }
        composeRule.onRoot().captureRoboImage(
            "../fastlane/metadata/android/en-US/images/phoneScreenshots/$name.png"
        )
    }

    @Test
    fun welcome() = capture("1_welcome") { WelcomeScreen() }

    @Test
    fun notifications() = capture("2_notifications") { NotificationPreferencesScreen() }

    @Test
    fun home() = capture("3_home") {
        MainDashboardScreen(state = homeConnectedState())
    }

    @Test
    fun settings() = capture("4_settings") {
        SettingsScreen(state = settingsConnectionClusterState(), showDeveloperSection = false)
    }

    @Test
    fun auditHistory() = capture("5_audit_history") {
        AuditHistoryScreen(state = auditHistoryState())
    }

    @Test
    fun auditDetail() = capture("6_audit_detail") {
        AuditDetailScreen(uiState = AuditDetailUiState.Loaded(auditDetailState()))
    }

    @Test
    fun healthConnect() = capture("7_health_connect") { HealthConnectSetupScreen() }

    @Test
    fun healthConnectClients() = capture("8_health_connect_clients") {
        IntegrationManageScreen(state = healthManageState())
    }

    @Test
    fun backgroundDelivery() = capture("9_background_delivery") {
        BackgroundDeliveryScreen(distributorOptions(), settingsMode = false, {}, {}, {})
    }

    // =========================================================================
    // State builders — clean, representative mock state.
    // =========================================================================

    private fun homeConnectedState() = DashboardState(
        connectionStatus = ConnectionStatus.CONNECTED,
        activeSessionCount = 1,
        integrations = listOf(
            IntegrationItem(
                id = "health",
                name = "Health Connect",
                status = IntegrationStatus.ACTIVE,
                url = "https://brave-health.my-device.rousecontext.com/mcp"
            ),
            IntegrationItem(
                id = "notifications",
                name = "Notifications",
                status = IntegrationStatus.ACTIVE,
                url = "https://swift-notifications.my-device.rousecontext.com/mcp"
            )
        ),
        recentActivity = listOf(
            AuditHistoryEntry(
                time = "10:32 AM",
                toolName = "health/get_steps",
                durationMs = 142,
                arguments = "{days: 7}",
                clientLabel = "Claude Desktop"
            ),
            AuditHistoryEntry(
                time = "10:31 AM",
                toolName = "health/get_sleep",
                durationMs = 89,
                arguments = "{days: 1}",
                clientLabel = "Claude Desktop"
            )
        )
    )

    private fun settingsConnectionClusterState() = SettingsState(
        idleTimeoutMinutes = 5,
        quickDisconnectSeconds = 30,
        batteryOptimizationExempt = true,
        showBatteryWarning = false,
        canIgnoreDailyLimit = true,
        ignoreDailyTimeLimit = false,
        backgroundDelivery = BackgroundDeliveryRowState("ntfy")
    )

    private fun auditHistoryState() = AuditHistoryState(
        groups = listOf(
            AuditHistoryGroup.ofEntries(
                "Today",
                listOf(
                    AuditHistoryEntry(
                        time = "10:32 AM",
                        toolName = "health/get_steps",
                        durationMs = 142,
                        arguments = "{days: 7}",
                        clientLabel = "Claude Desktop"
                    ),
                    AuditHistoryEntry(
                        time = "10:31 AM",
                        toolName = "health/get_sleep",
                        durationMs = 89,
                        arguments = "{days: 1}",
                        clientLabel = "Claude Desktop"
                    ),
                    AuditHistoryEntry(
                        time = "10:30 AM",
                        toolName = "health/get_heart_rate",
                        durationMs = 201,
                        arguments = "{days: 7}",
                        clientLabel = "Claude Desktop"
                    )
                )
            ),
            AuditHistoryGroup.ofEntries(
                "Yesterday",
                listOf(
                    AuditHistoryEntry(
                        time = "3:15 PM",
                        toolName = "notifications/list_active",
                        durationMs = 64,
                        arguments = "{}",
                        clientLabel = "Cursor"
                    )
                )
            )
        )
    )

    private fun auditDetailState() = AuditDetailState(
        toolName = "health/get_steps",
        provider = "health",
        timestampMillis = 1_712_400_000_000L,
        durationMs = 142,
        argumentsJson = """{"days":7,"metric":"steps"}""",
        resultJson = """{"total":52340,"average":7477}"""
    )

    private fun healthManageState() = IntegrationManageState(
        status = IntegrationStatus.ACTIVE,
        url = "https://brave-health.my-device.rousecontext.com/mcp",
        recentActivity = listOf(
            AuditHistoryEntry(
                time = "10:32 AM",
                toolName = "get_steps",
                durationMs = 142,
                arguments = "{days: 7}",
                clientLabel = "Claude Desktop"
            ),
            AuditHistoryEntry(
                time = "10:31 AM",
                toolName = "get_sleep",
                durationMs = 89,
                arguments = "{days: 1}",
                clientLabel = "Claude Desktop"
            )
        ),
        authorizedClients = listOf(
            AuthorizedClient("Claude Desktop", "Apr 2", "2 hours ago"),
            AuthorizedClient("Cursor", "Apr 3", "just now")
        )
    )

    private fun distributorOptions() = listOf(
        DistributorOption(
            "io.heckel.ntfy",
            "ntfy",
            "Recommended · not installed",
            DistributorOption.Kind.INSTALL_NTFY
        ),
        DistributorOption(
            "",
            "Install another app",
            "Browse delivery apps",
            DistributorOption.Kind.INSTALL_OTHER
        )
    )
}
