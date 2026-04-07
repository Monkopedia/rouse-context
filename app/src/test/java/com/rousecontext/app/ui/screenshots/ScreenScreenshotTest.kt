package com.rousecontext.app.ui.screenshots

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.screens.AddIntegrationPickerScreen
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.AuditHistoryEntry
import com.rousecontext.app.ui.screens.AuditHistoryGroup
import com.rousecontext.app.ui.screens.AuditHistoryScreen
import com.rousecontext.app.ui.screens.AuditHistoryState
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.ConnectionConfirmedScreen
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.DeviceCodeApprovalScreen
import com.rousecontext.app.ui.screens.DeviceCodeApprovalState
import com.rousecontext.app.ui.screens.HealthConnectSettingsScreen
import com.rousecontext.app.ui.screens.HealthConnectSettingsState
import com.rousecontext.app.ui.screens.HealthConnectSetupScreen
import com.rousecontext.app.ui.screens.HealthPermission
import com.rousecontext.app.ui.screens.IntegrationEnabledScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.screens.NotificationPreferencesScreen
import com.rousecontext.app.ui.screens.PickerIntegration
import com.rousecontext.app.ui.screens.PickerIntegrationState
import com.rousecontext.app.ui.screens.SettingUpScreen
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.SettingsScreen
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.theme.RouseContextTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w400dp-h800dp-xxhdpi")
class ScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) {
                content()
            }
        }
        composeRule.onRoot().captureRoboImage("screenshots/$name.png")
    }

    // 1. Welcome
    @Test
    fun welcome() = capture("01_welcome") {
        WelcomeScreen()
    }

    // 2. Dashboard - Empty
    @Test
    fun dashboardEmpty() = capture("02_dashboard_empty") {
        MainDashboardScreen(state = DashboardState())
    }

    // 2. Dashboard - With integrations
    @Test
    fun dashboardWithIntegrations() = capture("03_dashboard_with_integrations") {
        MainDashboardScreen(
            state = DashboardState(
                connectionStatus = ConnectionStatus.CONNECTED,
                activeSessionCount = 1,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.rousecontext.com/health"
                    ),
                    IntegrationItem(
                        id = "notifications",
                        name = "Notifications",
                        status = IntegrationStatus.PENDING,
                        url = "https://brave-falcon.rousecontext.com/notifications"
                    )
                ),
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "health/get_steps", 142),
                    AuditEntry("10:31 AM", "health/get_sleep", 89)
                )
            )
        )
    }

    // 2. Dashboard - Cert renewing
    @Test
    fun dashboardCertRenewing() = capture("04_dashboard_cert_renewing") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Renewing,
                integrations = listOf(
                    IntegrationItem(
                        "health",
                        "Health Connect",
                        IntegrationStatus.ACTIVE,
                        "https://brave-falcon.rousecontext.com/health"
                    )
                )
            )
        )
    }

    // 2. Dashboard - Cert expired (renewal failing)
    @Test
    fun dashboardCertExpiredFailing() = capture("05_dashboard_cert_expired_failing") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Expired(renewalInProgress = false),
                integrations = listOf(
                    IntegrationItem(
                        "health",
                        "Health Connect",
                        IntegrationStatus.ACTIVE,
                        "https://brave-falcon.rousecontext.com/health"
                    )
                )
            )
        )
    }

    // 2. Dashboard - Cert expired (renewing)
    @Test
    fun dashboardCertExpiredRenewing() = capture("06_dashboard_cert_expired_renewing") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Expired(renewalInProgress = true),
                integrations = listOf(
                    IntegrationItem(
                        "health",
                        "Health Connect",
                        IntegrationStatus.ACTIVE,
                        "https://brave-falcon.rousecontext.com/health"
                    )
                )
            )
        )
    }

    // 2. Dashboard - Rate limited
    @Test
    fun dashboardCertRateLimited() = capture("07_dashboard_cert_rate_limited") {
        MainDashboardScreen(
            state = DashboardState(certBanner = CertBanner.RateLimited(retryDate = "Apr 11"))
        )
    }

    // 2. Dashboard - Onboarding
    @Test
    fun dashboardCertOnboarding() = capture("08_dashboard_cert_onboarding") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Onboarding(
                    generatingKeysDone = true,
                    registeringDone = true,
                    issuingCert = true
                )
            )
        )
    }

    // 3. Add Integration Picker
    @Test
    fun addIntegrationPicker() = capture("09_add_integration_picker") {
        AddIntegrationPickerScreen(
            integrations = listOf(
                PickerIntegration(
                    "health",
                    "Health Connect",
                    "Share step count, heart rate, and sleep data with AI clients",
                    PickerIntegrationState.AVAILABLE
                ),
                PickerIntegration(
                    "notifications",
                    "Notifications",
                    "Let AI clients read your notifications",
                    PickerIntegrationState.UNAVAILABLE
                )
            )
        )
    }

    // 3. Add Integration Picker - with disabled
    @Test
    fun addIntegrationPickerWithDisabled() = capture("10_add_integration_picker_disabled") {
        AddIntegrationPickerScreen(
            integrations = listOf(
                PickerIntegration(
                    "health",
                    "Health Connect",
                    "Share step count, heart rate, and sleep data with AI clients",
                    PickerIntegrationState.DISABLED
                ),
                PickerIntegration(
                    "notifications",
                    "Notifications",
                    "Let AI clients read your notifications",
                    PickerIntegrationState.UNAVAILABLE
                )
            )
        )
    }

    // 4. Notification Preferences
    @Test
    fun notificationPreferences() = capture("11_notification_preferences") {
        NotificationPreferencesScreen()
    }

    // 5. Setting Up - First time
    @Test
    fun settingUpFirstTime() = capture("12_setting_up_first_time") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.FirstTime()))
    }

    // 5. Setting Up - Refreshing
    @Test
    fun settingUpRefreshing() = capture("13_setting_up_refreshing") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Refreshing))
    }

    // 5. Setting Up - Rate limited
    @Test
    fun settingUpRateLimited() = capture("14_setting_up_rate_limited") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.RateLimited("Apr 11")))
    }

    // 6. Integration Enabled
    @Test
    fun integrationEnabled() = capture("15_integration_enabled") {
        IntegrationEnabledScreen(
            state = IntegrationEnabledState(
                integrationName = "Health Connect",
                url = "https://brave-falcon.rousecontext.com/health"
            )
        )
    }

    // 7. Integration Manage - Active
    @Test
    fun integrationManageActive() = capture("16_integration_manage_active") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.ACTIVE,
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "get_steps", 142),
                    AuditEntry("10:31 AM", "get_sleep", 89)
                ),
                authorizedClients = listOf(
                    AuthorizedClient("Claude Desktop", "Apr 2", "2 hours ago"),
                    AuthorizedClient("Cursor", "Apr 3", "just now")
                )
            )
        )
    }

    // 7. Integration Manage - Pending
    @Test
    fun integrationManagePending() = capture("17_integration_manage_pending") {
        IntegrationManageScreen(
            state = IntegrationManageState(status = IntegrationStatus.PENDING)
        )
    }

    // 8. Device Code Approval - Empty
    @Test
    fun deviceCodeApprovalEmpty() = capture("18_device_code_approval_empty") {
        DeviceCodeApprovalScreen()
    }

    // 8. Device Code Approval - Filled
    @Test
    fun deviceCodeApprovalFilled() = capture("19_device_code_approval_filled") {
        DeviceCodeApprovalScreen(
            state = DeviceCodeApprovalState(enteredCode = "ABCD1234")
        )
    }

    // 9. Connection Confirmed
    @Test
    fun connectionConfirmed() = capture("20_connection_confirmed") {
        ConnectionConfirmedScreen()
    }

    // 10. Audit History - Populated
    @Test
    fun auditHistoryPopulated() = capture("21_audit_history_populated") {
        AuditHistoryScreen(
            state = AuditHistoryState(
                groups = listOf(
                    AuditHistoryGroup(
                        "Today",
                        listOf(
                            AuditHistoryEntry("10:32 AM", "health/get_steps", 142, "{days: 7}"),
                            AuditHistoryEntry("10:31 AM", "health/get_sleep", 89, "{days: 1}"),
                            AuditHistoryEntry(
                                "10:31 AM",
                                "health/get_heart_rate",
                                201,
                                "{days: 7}"
                            )
                        )
                    ),
                    AuditHistoryGroup(
                        "Yesterday",
                        listOf(
                            AuditHistoryEntry("3:15 PM", "health/get_steps", 156, "{days: 30}")
                        )
                    )
                )
            )
        )
    }

    // 10. Audit History - Empty
    @Test
    fun auditHistoryEmpty() = capture("22_audit_history_empty") {
        AuditHistoryScreen()
    }

    // 10. Audit History - Filtered
    @Test
    fun auditHistoryFiltered() = capture("23_audit_history_filtered") {
        AuditHistoryScreen(
            state = AuditHistoryState(
                providerFilter = "health",
                dateFilter = "Last 7 days",
                groups = listOf(
                    AuditHistoryGroup(
                        "Today",
                        listOf(
                            AuditHistoryEntry("10:32 AM", "health/get_steps", 142, "{days: 7}")
                        )
                    )
                )
            )
        )
    }

    // 11. Settings
    @Test
    fun settings() = capture("24_settings") {
        SettingsScreen()
    }

    // 11. Settings - No battery warning
    @Test
    fun settingsNoBatteryWarning() = capture("25_settings_no_battery_warning") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true
            )
        )
    }

    // 11. Settings - Trust status verified
    @Test
    fun settingsTrustVerified() = capture("28_settings_trust_verified") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true,
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 7_200_000,
                    selfCheckResult = "verified",
                    ctCheckResult = "verified",
                    certFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                    overallStatus = TrustOverallStatus.VERIFIED
                )
            )
        )
    }

    // 11. Settings - Trust status warning
    @Test
    fun settingsTrustWarning() = capture("29_settings_trust_warning") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true,
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 7_200_000,
                    selfCheckResult = "verified",
                    ctCheckResult = "warning",
                    certFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                    overallStatus = TrustOverallStatus.WARNING
                )
            )
        )
    }

    // 11. Settings - Trust status alert
    @Test
    fun settingsTrustAlert() = capture("30_settings_trust_alert") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true,
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 7_200_000,
                    selfCheckResult = "verified",
                    ctCheckResult = "alert",
                    certFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                    overallStatus = TrustOverallStatus.ALERT
                )
            )
        )
    }

    // 12. Health Connect Setup
    @Test
    fun healthConnectSetup() = capture("26_health_connect_setup") {
        HealthConnectSetupScreen()
    }

    // 13. Health Connect Settings
    @Test
    fun healthConnectSettings() = capture("27_health_connect_settings") {
        HealthConnectSettingsScreen(
            state = HealthConnectSettingsState(
                permissions = listOf(
                    HealthPermission("Steps", granted = true),
                    HealthPermission("Heart rate", granted = true),
                    HealthPermission("Sleep", granted = true),
                    HealthPermission("Workout history", granted = false),
                    HealthPermission("HRV", granted = false)
                )
            )
        )
    }
}
