package com.rousecontext.app.ui.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.rousecontext.app.ui.screens.AddIntegrationPickerScreen
import com.rousecontext.app.ui.screens.AuditDetailScreen
import com.rousecontext.app.ui.screens.AuditDetailState
import com.rousecontext.app.ui.screens.AuditDetailUiState
import com.rousecontext.app.ui.screens.AuditEntry
import com.rousecontext.app.ui.screens.AuditHistoryEntry
import com.rousecontext.app.ui.screens.AuditHistoryGroup
import com.rousecontext.app.ui.screens.AuditHistoryItem
import com.rousecontext.app.ui.screens.AuditHistoryScreen
import com.rousecontext.app.ui.screens.AuditHistoryState
import com.rousecontext.app.ui.screens.AuthorizationApprovalItem
import com.rousecontext.app.ui.screens.AuthorizationApprovalScreen
import com.rousecontext.app.ui.screens.AuthorizedClient
import com.rousecontext.app.ui.screens.CertBanner
import com.rousecontext.app.ui.screens.ConnectionStatus
import com.rousecontext.app.ui.screens.DashboardState
import com.rousecontext.app.ui.screens.HealthConnectSetupContent
import com.rousecontext.app.ui.screens.HealthConnectSetupScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledScreen
import com.rousecontext.app.ui.screens.IntegrationEnabledState
import com.rousecontext.app.ui.screens.IntegrationItem
import com.rousecontext.app.ui.screens.IntegrationManageScreen
import com.rousecontext.app.ui.screens.IntegrationManageState
import com.rousecontext.app.ui.screens.IntegrationStatus
import com.rousecontext.app.ui.screens.MainDashboardScreen
import com.rousecontext.app.ui.screens.NotificationBanner
import com.rousecontext.app.ui.screens.NotificationPreferencesScreen
import com.rousecontext.app.ui.screens.NotificationSetupScreen
import com.rousecontext.app.ui.screens.OutreachSetupScreen
import com.rousecontext.app.ui.screens.PickerIntegration
import com.rousecontext.app.ui.screens.PickerIntegrationState
import com.rousecontext.app.ui.screens.SettingUpScreen
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.app.ui.screens.SettingsScreen
import com.rousecontext.app.ui.screens.SettingsState
import com.rousecontext.app.ui.screens.SetupMode
import com.rousecontext.app.ui.screens.TerminalReason
import com.rousecontext.app.ui.screens.TrustOverallStatus
import com.rousecontext.app.ui.screens.TrustStatusSection
import com.rousecontext.app.ui.screens.TrustStatusState
import com.rousecontext.app.ui.screens.UsageSetupScreen
import com.rousecontext.app.ui.screens.WelcomeScreen
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.viewmodels.NotificationSetupState
import com.rousecontext.app.ui.viewmodels.OutreachSetupState
import com.rousecontext.app.ui.viewmodels.UsageSetupState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen in both light and dark theme, saving PNGs to
 * `app/screenshots/` via Roborazzi. Run with:
 *
 * ```
 * JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
 *   ./gradlew :app:testDebugUnitTest \
 *   --tests "com.rousecontext.app.ui.screenshots.*"
 * ```
 *
 * Then open `docs/screenshots/index.html` to browse the gallery.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    sdk = [33],
    qualifiers = "w400dp-h800dp-xxhdpi",
    application = com.rousecontext.app.TestApplication::class
)
class ScreenScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun captureDark(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = true) { content() }
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_dark.png")
    }

    private fun captureLight(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            RouseContextTheme(darkTheme = false) { content() }
        }
        composeRule.onRoot().captureRoboImage("screenshots/${name}_light.png")
    }

    // =========================================================================
    // Onboarding
    // =========================================================================

    @Test
    fun welcomeDark() = captureDark("01_welcome") { WelcomeScreen() }

    @Test
    fun welcomeLight() = captureLight("01_welcome") { WelcomeScreen() }

    @Test
    fun settingUpFirstTimeDark() = captureDark("02_setting_up_first_time") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Registering))
    }

    @Test
    fun settingUpFirstTimeLight() = captureLight("02_setting_up_first_time") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Registering))
    }

    @Test
    fun settingUpRefreshingDark() = captureDark("03_setting_up_refreshing") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Requesting))
    }

    @Test
    fun settingUpRefreshingLight() = captureLight("03_setting_up_refreshing") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.Requesting))
    }

    @Test
    fun settingUpRateLimitedDark() = captureDark("04_setting_up_rate_limited") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.RateLimited("Apr 11")))
    }

    @Test
    fun settingUpRateLimitedLight() = captureLight("04_setting_up_rate_limited") {
        SettingUpScreen(state = SettingUpState(SettingUpVariant.RateLimited("Apr 11")))
    }

    @Test
    fun settingUpFailedDark() = captureDark("05_setting_up_failed") {
        SettingUpScreen(
            state = SettingUpState(
                SettingUpVariant.Failed(
                    "Couldn't register integration with relay. Try again."
                )
            ),
            onRetry = {}
        )
    }

    @Test
    fun settingUpFailedLight() = captureLight("05_setting_up_failed") {
        SettingUpScreen(
            state = SettingUpState(
                SettingUpVariant.Failed(
                    "Couldn't register integration with relay. Try again."
                )
            ),
            onRetry = {}
        )
    }

    // =========================================================================
    // Dashboard
    // =========================================================================

    @Test
    fun dashboardEmptyDark() = captureDark("07_dashboard_empty") {
        MainDashboardScreen(state = DashboardState())
    }

    @Test
    fun dashboardEmptyLight() = captureLight("07_dashboard_empty") {
        MainDashboardScreen(state = DashboardState())
    }

    @Test
    fun dashboardWithIntegrationsDark() = captureDark("08_dashboard_integrations") {
        MainDashboardScreen(state = dashboardWithIntegrationsState())
    }

    @Test
    fun dashboardWithIntegrationsLight() = captureLight("08_dashboard_integrations") {
        MainDashboardScreen(state = dashboardWithIntegrationsState())
    }

    @Test
    fun dashboardCertRenewingDark() = captureDark("09_dashboard_cert_renewing") {
        MainDashboardScreen(state = dashboardCertState(CertBanner.Renewing))
    }

    @Test
    fun dashboardCertRenewingLight() = captureLight("09_dashboard_cert_renewing") {
        MainDashboardScreen(state = dashboardCertState(CertBanner.Renewing))
    }

    @Test
    fun dashboardCertExpiredFailingDark() = captureDark("10_dashboard_cert_expired_failing") {
        MainDashboardScreen(
            state = dashboardCertState(CertBanner.Expired(renewalInProgress = false))
        )
    }

    @Test
    fun dashboardCertExpiredFailingLight() = captureLight("10_dashboard_cert_expired_failing") {
        MainDashboardScreen(
            state = dashboardCertState(CertBanner.Expired(renewalInProgress = false))
        )
    }

    @Test
    fun dashboardCertExpiredRenewingDark() = captureDark("11_dashboard_cert_expired_renewing") {
        MainDashboardScreen(
            state = dashboardCertState(CertBanner.Expired(renewalInProgress = true))
        )
    }

    @Test
    fun dashboardCertExpiredRenewingLight() = captureLight("11_dashboard_cert_expired_renewing") {
        MainDashboardScreen(
            state = dashboardCertState(CertBanner.Expired(renewalInProgress = true))
        )
    }

    @Test
    fun dashboardCertRateLimitedDark() = captureDark("12_dashboard_cert_rate_limited") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.RateLimited(retryDate = "Apr 11")
            )
        )
    }

    @Test
    fun dashboardCertRateLimitedLight() = captureLight("12_dashboard_cert_rate_limited") {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.RateLimited(retryDate = "Apr 11")
            )
        )
    }

    @Test
    fun dashboardCertTerminalKeyGenDark() = captureDark("13a_dashboard_cert_terminal_keygen") {
        MainDashboardScreen(
            state = dashboardCertState(
                CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)
            )
        )
    }

    @Test
    fun dashboardCertTerminalKeyGenLight() = captureLight("13a_dashboard_cert_terminal_keygen") {
        MainDashboardScreen(
            state = dashboardCertState(
                CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)
            )
        )
    }

    @Test
    fun dashboardCertTerminalCnMismatchDark() = captureDark("13b_dashboard_cert_terminal_cn") {
        MainDashboardScreen(
            state = dashboardCertState(
                CertBanner.TerminalFailure(TerminalReason.CnMismatch)
            )
        )
    }

    @Test
    fun dashboardCertTerminalCnMismatchLight() = captureLight("13b_dashboard_cert_terminal_cn") {
        MainDashboardScreen(
            state = dashboardCertState(
                CertBanner.TerminalFailure(TerminalReason.CnMismatch)
            )
        )
    }

    @Test
    fun dashboardCertOnboardingDark() = captureDark("13_dashboard_cert_onboarding") {
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

    @Test
    fun dashboardCertOnboardingLight() = captureLight("13_dashboard_cert_onboarding") {
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

    @Test
    fun dashboardNotificationDeniedDark() = captureDark("13c_dashboard_notification_denied") {
        MainDashboardScreen(
            state = DashboardState(notificationBanner = NotificationBanner)
        )
    }

    @Test
    fun dashboardNotificationDeniedLight() = captureLight("13c_dashboard_notification_denied") {
        MainDashboardScreen(
            state = DashboardState(notificationBanner = NotificationBanner)
        )
    }

    // =========================================================================
    // Integrations
    // =========================================================================

    @Test
    fun addIntegrationPickerDark() = captureDark("14_add_integration_picker") {
        AddIntegrationPickerScreen(integrations = pickerIntegrations())
    }

    @Test
    fun addIntegrationPickerLight() = captureLight("14_add_integration_picker") {
        AddIntegrationPickerScreen(integrations = pickerIntegrations())
    }

    @Test
    fun integrationEnabledDark() = captureDark("16_integration_enabled") {
        IntegrationEnabledScreen(
            state = IntegrationEnabledState(
                integrationName = "Health Connect",
                url = "https://brave-health.my-device.rousecontext.com/mcp"
            )
        )
    }

    @Test
    fun integrationEnabledLight() = captureLight("16_integration_enabled") {
        IntegrationEnabledScreen(
            state = IntegrationEnabledState(
                integrationName = "Health Connect",
                url = "https://brave-health.my-device.rousecontext.com/mcp"
            )
        )
    }

    @Test
    fun integrationManageActiveDark() = captureDark("17_integration_manage_active") {
        IntegrationManageScreen(state = integrationManageActiveState())
    }

    @Test
    fun integrationManageActiveLight() = captureLight("17_integration_manage_active") {
        IntegrationManageScreen(state = integrationManageActiveState())
    }

    @Test
    fun integrationManageManyClientsDark() = captureDark("18a_integration_manage_many_clients") {
        IntegrationManageScreen(state = integrationManageManyClientsState())
    }

    @Test
    fun integrationManagePendingDark() = captureDark("18_integration_manage_pending") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.PENDING,
                url = "https://brave-health.my-device.rousecontext.com/mcp"
            )
        )
    }

    @Test
    fun integrationManagePendingLight() = captureLight("18_integration_manage_pending") {
        IntegrationManageScreen(
            state = IntegrationManageState(
                status = IntegrationStatus.PENDING,
                url = "https://brave-health.my-device.rousecontext.com/mcp"
            )
        )
    }

    // =========================================================================
    // Auth
    // =========================================================================

    @Test
    fun authorizationApprovalEmptyDark() = captureDark("21_authorization_approval_empty") {
        AuthorizationApprovalScreen()
    }

    @Test
    fun authorizationApprovalEmptyLight() = captureLight("21_authorization_approval_empty") {
        AuthorizationApprovalScreen()
    }

    @Test
    fun authorizationApprovalRequestsDark() = captureDark("22_authorization_approval_requests") {
        AuthorizationApprovalScreen(pendingRequests = authApprovalRequests())
    }

    @Test
    fun authorizationApprovalRequestsLight() = captureLight("22_authorization_approval_requests") {
        AuthorizationApprovalScreen(pendingRequests = authApprovalRequests())
    }

    // =========================================================================
    // Health Connect
    // =========================================================================

    @Test
    fun healthConnectSetupDark() = captureDark("25_health_connect_setup") {
        HealthConnectSetupScreen()
    }

    @Test
    fun healthConnectSetupLight() = captureLight("25_health_connect_setup") {
        HealthConnectSetupScreen()
    }

    @Test
    fun healthConnectSettingsDark() = captureDark("26_health_connect_settings") {
        HealthConnectSetupContent(
            mode = SetupMode.SETTINGS,
            historicalAccessGranted = true,
            grantedRecordTypes = SAMPLE_GRANTED_RECORD_TYPES
        )
    }

    @Test
    fun healthConnectSettingsLight() = captureLight("26_health_connect_settings") {
        HealthConnectSetupContent(
            mode = SetupMode.SETTINGS,
            historicalAccessGranted = true,
            grantedRecordTypes = SAMPLE_GRANTED_RECORD_TYPES
        )
    }

    // =========================================================================
    // Notification Setup
    // =========================================================================

    @Test
    fun notificationSetupDark() = captureDark("27_notification_setup") {
        NotificationSetupScreen()
    }

    @Test
    fun notificationSetupLight() = captureLight("27_notification_setup") {
        NotificationSetupScreen()
    }

    @Test
    fun notificationSetupGrantedDark() = captureDark("28_notification_setup_granted") {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 30,
                allowActions = true
            )
        )
    }

    @Test
    fun notificationSetupGrantedLight() = captureLight("28_notification_setup_granted") {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 30,
                allowActions = true
            )
        )
    }

    @Test
    fun notificationPreferencesDark() = captureDark("29_notification_preferences") {
        NotificationPreferencesScreen()
    }

    @Test
    fun notificationPreferencesLight() = captureLight("29_notification_preferences") {
        NotificationPreferencesScreen()
    }

    // =========================================================================
    // Outreach Setup
    // =========================================================================

    @Test
    fun outreachSetupDark() = captureDark("30_outreach_setup") {
        OutreachSetupScreen()
    }

    @Test
    fun outreachSetupLight() = captureLight("30_outreach_setup") {
        OutreachSetupScreen()
    }

    @Test
    fun outreachSetupDndDark() = captureDark("31_outreach_setup_dnd") {
        OutreachSetupScreen(
            state = OutreachSetupState(dndToggled = true, dndPermissionGranted = true)
        )
    }

    @Test
    fun outreachSetupDndLight() = captureLight("31_outreach_setup_dnd") {
        OutreachSetupScreen(
            state = OutreachSetupState(dndToggled = true, dndPermissionGranted = true)
        )
    }

    // Android 14+ only: SYSTEM_ALERT_WINDOW opt-in section for background
    // activity launches. See issue #102.
    @Test
    fun outreachSetupDirectLaunchUngrantedDark() =
        captureDark("31a_outreach_setup_direct_launch_ungranted") {
            OutreachSetupScreen(
                state = OutreachSetupState(
                    directLaunchApplicable = true,
                    directLaunchEnabled = true,
                    overlayPermissionGranted = false
                )
            )
        }

    @Test
    fun outreachSetupDirectLaunchUngrantedLight() =
        captureLight("31a_outreach_setup_direct_launch_ungranted") {
            OutreachSetupScreen(
                state = OutreachSetupState(
                    directLaunchApplicable = true,
                    directLaunchEnabled = true,
                    overlayPermissionGranted = false
                )
            )
        }

    @Test
    fun outreachSetupDirectLaunchGrantedDark() =
        captureDark("31b_outreach_setup_direct_launch_granted") {
            OutreachSetupScreen(
                state = OutreachSetupState(
                    directLaunchApplicable = true,
                    directLaunchEnabled = true,
                    overlayPermissionGranted = true
                )
            )
        }

    @Test
    fun outreachSetupDirectLaunchGrantedLight() =
        captureLight("31b_outreach_setup_direct_launch_granted") {
            OutreachSetupScreen(
                state = OutreachSetupState(
                    directLaunchApplicable = true,
                    directLaunchEnabled = true,
                    overlayPermissionGranted = true
                )
            )
        }

    // =========================================================================
    // Usage Setup
    // =========================================================================

    @Test
    fun usageSetupDark() = captureDark("32_usage_setup") {
        UsageSetupScreen()
    }

    @Test
    fun usageSetupLight() = captureLight("32_usage_setup") {
        UsageSetupScreen()
    }

    @Test
    fun usageSetupGrantedDark() = captureDark("33_usage_setup_granted") {
        UsageSetupScreen(state = UsageSetupState(permissionGranted = true))
    }

    @Test
    fun usageSetupGrantedLight() = captureLight("33_usage_setup_granted") {
        UsageSetupScreen(state = UsageSetupState(permissionGranted = true))
    }

    // =========================================================================
    // Settings-mode variants (floating Save bar — #59)
    // These render the same screens in SETTINGS mode with dirty state so the
    // pinned FloatingSaveBar is visible at the bottom.
    // =========================================================================

    @Test
    fun notificationSettingsDirtyDark() = captureDark("33a_notification_settings_dirty") {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 30,
                allowActions = true
            ),
            mode = SetupMode.SETTINGS,
            isDirty = true
        )
    }

    @Test
    fun notificationSettingsDirtyLight() = captureLight("33a_notification_settings_dirty") {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 30,
                allowActions = true
            ),
            mode = SetupMode.SETTINGS,
            isDirty = true
        )
    }

    @Test
    fun notificationSettingsCleanDark() = captureDark("33b_notification_settings_clean") {
        NotificationSetupScreen(
            state = NotificationSetupState(
                permissionGranted = true,
                retentionDays = 7,
                allowActions = false
            ),
            mode = SetupMode.SETTINGS,
            isDirty = false
        )
    }

    @Test
    fun outreachSettingsDirtyDark() = captureDark("33c_outreach_settings_dirty") {
        OutreachSetupScreen(
            state = OutreachSetupState(
                dndToggled = true,
                dndPermissionGranted = true
            ),
            mode = SetupMode.SETTINGS,
            isDirty = true
        )
    }

    @Test
    fun outreachSettingsDirtyLight() = captureLight("33c_outreach_settings_dirty") {
        OutreachSetupScreen(
            state = OutreachSetupState(
                dndToggled = true,
                dndPermissionGranted = true
            ),
            mode = SetupMode.SETTINGS,
            isDirty = true
        )
    }

    // =========================================================================
    // Audit History
    // =========================================================================

    @Test
    fun auditHistoryPopulatedDark() = captureDark("34_audit_history_populated") {
        AuditHistoryScreen(state = auditHistoryPopulatedState())
    }

    @Test
    fun auditHistoryPopulatedLight() = captureLight("34_audit_history_populated") {
        AuditHistoryScreen(state = auditHistoryPopulatedState())
    }

    @Test
    fun auditHistoryEmptyDark() = captureDark("35_audit_history_empty") {
        AuditHistoryScreen()
    }

    @Test
    fun auditHistoryEmptyLight() = captureLight("35_audit_history_empty") {
        AuditHistoryScreen()
    }

    @Test
    fun auditHistoryFilteredDark() = captureDark("36_audit_history_filtered") {
        AuditHistoryScreen(state = auditHistoryFilteredState())
    }

    @Test
    fun auditHistoryFilteredLight() = captureLight("36_audit_history_filtered") {
        AuditHistoryScreen(state = auditHistoryFilteredState())
    }

    @Test
    fun auditHistoryShowAllMcpMessagesDark() =
        captureDark("36a_audit_history_show_all_mcp_messages") {
            AuditHistoryScreen(state = auditHistoryShowAllState())
        }

    @Test
    fun auditHistoryShowAllMcpMessagesLight() =
        captureLight("36a_audit_history_show_all_mcp_messages") {
            AuditHistoryScreen(state = auditHistoryShowAllState())
        }

    // =========================================================================
    // Settings
    // =========================================================================

    @Test
    fun settingsDark() = captureDark("37_settings") { SettingsScreen() }

    @Test
    fun settingsLight() = captureLight("37_settings") { SettingsScreen() }

    @Test
    fun settingsNoBatteryWarningDark() = captureDark("38_settings_no_battery") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true
            )
        )
    }

    @Test
    fun settingsNoBatteryWarningLight() = captureLight("38_settings_no_battery") {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true
            )
        )
    }

    @Test
    fun settingsTrustVerifiedDark() = captureDark("39_settings_trust_verified") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.VERIFIED, "verified"))
    }

    @Test
    fun settingsTrustVerifiedLight() = captureLight("39_settings_trust_verified") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.VERIFIED, "verified"))
    }

    @Test
    fun settingsTrustWarningDark() = captureDark("40_settings_trust_warning") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.WARNING, "warning"))
    }

    @Test
    fun settingsTrustWarningLight() = captureLight("40_settings_trust_warning") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.WARNING, "warning"))
    }

    @Test
    fun settingsTrustAlertDark() = captureDark("41_settings_trust_alert") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.ALERT, "alert"))
    }

    @Test
    fun settingsTrustAlertLight() = captureLight("41_settings_trust_alert") {
        SettingsScreen(state = settingsTrustState(TrustOverallStatus.ALERT, "alert"))
    }

    // Isolated trust-card renderings for the user-facing docs site. Each
    // captures only the TrustStatusSection card inside an 8dp app-background
    // border so the PNG reads as a minimal cropped screenshot without the
    // rest of the Settings chrome.

    @Test
    fun trustCardWarningLight() = captureLight("50a_trust_card_warning") {
        TrustCardDocsFrame(TrustOverallStatus.WARNING, ctResult = "warning")
    }

    @Test
    fun trustCardWarningDark() = captureDark("50a_trust_card_warning") {
        TrustCardDocsFrame(TrustOverallStatus.WARNING, ctResult = "warning")
    }

    @Test
    fun trustCardAlertLight() = captureLight("50b_trust_card_alert") {
        TrustCardDocsFrame(TrustOverallStatus.ALERT, ctResult = "alert")
    }

    @Test
    fun trustCardAlertDark() = captureDark("50b_trust_card_alert") {
        TrustCardDocsFrame(TrustOverallStatus.ALERT, ctResult = "alert")
    }

    // =========================================================================
    // Audit Detail
    // =========================================================================

    @Test
    fun auditDetailLoadingDark() = captureDark("42_audit_detail_loading") {
        AuditDetailScreen()
    }

    @Test
    fun auditDetailLoadingLight() = captureLight("42_audit_detail_loading") {
        AuditDetailScreen()
    }

    @Test
    fun auditDetailPopulatedDark() = captureDark("43_audit_detail_populated") {
        AuditDetailScreen(uiState = AuditDetailUiState.Loaded(auditDetailPopulatedState()))
    }

    @Test
    fun auditDetailPopulatedLight() = captureLight("43_audit_detail_populated") {
        AuditDetailScreen(uiState = AuditDetailUiState.Loaded(auditDetailPopulatedState()))
    }

    @Test
    fun auditDetailNotFoundDark() = captureDark("44_audit_detail_not_found") {
        AuditDetailScreen(uiState = AuditDetailUiState.NotFound)
    }

    @Test
    fun auditDetailNotFoundLight() = captureLight("44_audit_detail_not_found") {
        AuditDetailScreen(uiState = AuditDetailUiState.NotFound)
    }

    // =========================================================================
    // Shared state builders
    // =========================================================================

    private fun dashboardWithIntegrationsState() = DashboardState(
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
                status = IntegrationStatus.PENDING,
                url = "https://swift-notifications.my-device.rousecontext.com/mcp"
            )
        ),
        recentActivity = listOf(
            AuditEntry("10:32 AM", "health/get_steps", 142),
            AuditEntry("10:31 AM", "health/get_sleep", 89)
        )
    )

    private fun dashboardCertState(banner: CertBanner) = DashboardState(
        certBanner = banner,
        integrations = listOf(
            IntegrationItem(
                "health",
                "Health Connect",
                IntegrationStatus.ACTIVE,
                "https://brave-health.my-device.rousecontext.com/mcp"
            )
        )
    )

    private fun pickerIntegrations() = listOf(
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
            PickerIntegrationState.AVAILABLE
        )
    )

    private fun integrationManageActiveState() = IntegrationManageState(
        status = IntegrationStatus.ACTIVE,
        url = "https://brave-health.my-device.rousecontext.com/mcp",
        recentActivity = listOf(
            AuditEntry("10:32 AM", "get_steps", 142),
            AuditEntry("10:31 AM", "get_sleep", 89)
        ),
        authorizedClients = listOf(
            AuthorizedClient("Claude Desktop", "Apr 2", "2 hours ago"),
            AuthorizedClient("Cursor", "Apr 3", "just now")
        )
    )

    private fun integrationManageManyClientsState() = IntegrationManageState(
        status = IntegrationStatus.ACTIVE,
        url = "https://brave-falcon.my-device.rousecontext.com/health/mcp",
        recentActivity = listOf(
            AuditEntry("10:32 AM", "get_steps", 142),
            AuditEntry("10:31 AM", "get_sleep", 89),
            AuditEntry("10:30 AM", "get_heart_rate", 201)
        ),
        authorizedClients = listOf(
            AuthorizedClient("Claude", "Apr 2", "2 hours ago"),
            AuthorizedClient("Cursor", "Apr 3", "just now"),
            AuthorizedClient("Windsurf", "Apr 4", "yesterday"),
            AuthorizedClient("Cline", "Apr 5", "3 days ago"),
            AuthorizedClient("Continue", "Apr 6", "5 days ago"),
            AuthorizedClient("Aider", "Apr 7", "1 week ago")
        )
    )

    private fun authApprovalRequests() = listOf(
        AuthorizationApprovalItem(
            displayCode = "AB3X-9K2F",
            integration = "Health Connect"
        ),
        AuthorizationApprovalItem(
            displayCode = "7YMN-4HPQ",
            integration = "Notifications"
        )
    )

    private fun auditHistoryPopulatedState() = AuditHistoryState(
        groups = listOf(
            AuditHistoryGroup.ofEntries(
                "Today",
                listOf(
                    AuditHistoryEntry(
                        time = "10:32 AM",
                        toolName = "health/get_steps",
                        durationMs = 142,
                        arguments = "{days: 7}"
                    ),
                    AuditHistoryEntry(
                        time = "10:31 AM",
                        toolName = "health/get_sleep",
                        durationMs = 89,
                        arguments = "{days: 1}"
                    ),
                    AuditHistoryEntry(
                        time = "10:31 AM",
                        toolName = "health/get_heart_rate",
                        durationMs = 201,
                        arguments = "{days: 7}"
                    )
                )
            ),
            AuditHistoryGroup.ofEntries(
                "Yesterday",
                listOf(
                    AuditHistoryEntry(
                        time = "3:15 PM",
                        toolName = "health/get_steps",
                        durationMs = 156,
                        arguments = "{days: 30}"
                    )
                )
            )
        )
    )

    private fun auditHistoryFilteredState() = AuditHistoryState(
        providerFilter = com.rousecontext.app.ui.screens.ProviderFilterOption.Specific("health"),
        dateFilter = com.rousecontext.app.ui.screens.DateFilterOption.LAST_7_DAYS,
        groups = listOf(
            AuditHistoryGroup.ofEntries(
                "Today",
                listOf(
                    AuditHistoryEntry(
                        time = "10:32 AM",
                        toolName = "health/get_steps",
                        durationMs = 142,
                        arguments = "{days: 7}"
                    )
                )
            )
        )
    )

    private fun auditHistoryShowAllState() = AuditHistoryState(
        showAllMcpMessages = true,
        groups = listOf(
            AuditHistoryGroup(
                dateLabel = "Today",
                items = listOf(
                    AuditHistoryItem.Request(
                        id = 1,
                        time = "10:33 AM",
                        method = "initialize",
                        provider = "health",
                        durationMs = 5,
                        resultBytes = 320,
                        timestampMillis = 1_712_400_000_000L + 6_000
                    ),
                    AuditHistoryItem.ToolCall(
                        AuditHistoryEntry(
                            time = "10:32 AM",
                            toolName = "health/get_steps",
                            durationMs = 142,
                            arguments = "{days: 7}",
                            timestampMillis = 1_712_400_000_000L + 5_000
                        )
                    ),
                    AuditHistoryItem.Request(
                        id = 2,
                        time = "10:32 AM",
                        method = "tools/list",
                        provider = "health",
                        durationMs = 8,
                        resultBytes = 1_280,
                        timestampMillis = 1_712_400_000_000L + 4_000
                    ),
                    AuditHistoryItem.ToolCall(
                        AuditHistoryEntry(
                            time = "10:31 AM",
                            toolName = "health/get_sleep",
                            durationMs = 89,
                            arguments = "{days: 1}",
                            timestampMillis = 1_712_400_000_000L + 3_000
                        )
                    ),
                    AuditHistoryItem.Request(
                        id = 3,
                        time = "10:31 AM",
                        method = "prompts/get",
                        provider = "health",
                        durationMs = 3,
                        resultBytes = 64,
                        timestampMillis = 1_712_400_000_000L + 2_000
                    )
                )
            )
        )
    )

    private fun auditDetailPopulatedState() = AuditDetailState(
        toolName = "health/get_steps",
        provider = "health",
        // Fixed timestamp for reproducible screenshots
        timestampMillis = 1_712_400_000_000L,
        durationMs = 142,
        argumentsJson = """{"days":7,"metric":"steps"}""",
        resultJson = """{"total":52340,"average":7477}"""
    )

    private fun settingsTrustState(overall: TrustOverallStatus, ctResult: String) = SettingsState(
        showBatteryWarning = false,
        batteryOptimizationExempt = true,
        trustStatus = TrustStatusState(
            lastCheckTime = System.currentTimeMillis() - 7_200_000,
            selfCheckResult = "verified",
            ctCheckResult = ctResult,
            certFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
            overallStatus = overall
        )
    )

    @Composable
    private fun TrustCardDocsFrame(overall: TrustOverallStatus, ctResult: String) {
        Box(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp)
                .width(360.dp)
        ) {
            TrustStatusSection(
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 7_200_000,
                    selfCheckResult = "verified",
                    ctCheckResult = ctResult,
                    certFingerprint = "AA:BB:CC:DD:EE:FF:00:11:22:33:44:55:66:77:88:99",
                    overallStatus = overall
                )
            )
        }
    }
}

/**
 * Representative granted-permission set for the Health Connect settings
 * screenshot (#99): a mix across several categories so the rendered screenshot
 * exercises both granted (green check) and ungranted (outlined) row styles,
 * and shows a partial `granted/total` count in at least one category header.
 */
private val SAMPLE_GRANTED_RECORD_TYPES: Set<String> = setOf(
    // Activity: 2 of many
    "Steps",
    "ExerciseSession",
    // Body: 1 of several
    "Weight",
    // Sleep: all (only one type)
    "SleepSession",
    // Vitals: a couple
    "HeartRate",
    "RestingHeartRate"
)
