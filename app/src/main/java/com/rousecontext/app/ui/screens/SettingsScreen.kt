package com.rousecontext.app.ui.screens

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.ErrorState
import com.rousecontext.app.ui.components.LoadingIndicator
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.SwitchRow
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.theme.LocalExtendedColors
import com.rousecontext.app.ui.theme.RouseContextTheme
import com.rousecontext.app.ui.theme.SuccessGreen

enum class TrustOverallStatus {
    VERIFIED,
    WARNING,
    ALERT
}

/**
 * Post-session audit notification mode. The ViewModel emits one of these
 * variants; the UI resolves a user-facing label via [labelRes]. Stored in
 * DataStore by `name` (see [com.rousecontext.api.PostSessionMode]).
 */
enum class PostSessionModeOption {
    SUMMARY,
    EACH_USAGE,
    SUPPRESS;

    fun labelRes(): Int = when (this) {
        SUMMARY -> R.string.screen_settings_post_session_summary
        EACH_USAGE -> R.string.screen_settings_post_session_each_usage
        SUPPRESS -> R.string.screen_settings_post_session_suppress
    }
}

/**
 * Theme selector option. The ViewModel emits one of these variants; the UI
 * resolves a user-facing label via [labelRes].
 */
enum class ThemeModeOption {
    LIGHT,
    DARK,
    AUTO;

    fun labelRes(): Int = when (this) {
        LIGHT -> R.string.screen_settings_theme_light
        DARK -> R.string.screen_settings_theme_dark
        AUTO -> R.string.screen_settings_theme_auto
    }
}

/**
 * Discrete options for the security self-check cadence. Persisted as the
 * underlying [hours] value via [com.rousecontext.app.state.AppStatePreferences].
 */
enum class SecurityCheckIntervalOption(val hours: Int) {
    HOURS_6(6),
    HOURS_12(12),
    HOURS_24(24);

    companion object {
        /** Snap an arbitrary hour count to the nearest supported option. */
        fun forHours(hours: Int): SecurityCheckIntervalOption =
            entries.firstOrNull { it.hours == hours } ?: HOURS_12
    }
}

@Immutable
data class TrustStatusState(
    val lastCheckTime: Long = 0L,
    val selfCheckResult: String = "",
    val ctCheckResult: String = "",
    val certFingerprint: String = "",
    val overallStatus: TrustOverallStatus = TrustOverallStatus.VERIFIED
)

@Immutable
data class SettingsState(
    val idleTimeoutMinutes: Int = 5,
    val idleTimeoutDisabled: Boolean = false,
    val batteryOptimizationExempt: Boolean = false,
    val postSessionMode: PostSessionModeOption = PostSessionModeOption.SUMMARY,
    val themeMode: ThemeModeOption = ThemeModeOption.AUTO,
    val securityCheckInterval: SecurityCheckIntervalOption = SecurityCheckIntervalOption.HOURS_12,
    val canRotateAddress: Boolean = true,
    val rotationCooldownMessage: String? = null,
    val showBatteryWarning: Boolean = true,
    val versionName: String = "0.1.0",
    val trustStatus: TrustStatusState? = null,
    /**
     * Whether the audit history surface should include every MCP JSON-RPC
     * request, not just tool calls. See issue #105/#107.
     */
    val showAllMcpMessages: Boolean = false,
    /**
     * Count of wake cycles in the last 24h where the service woke but no MCP
     * stream ever opened. Non-zero values surface a read-only diagnostic row.
     */
    val spuriousWakesLast24h: Int = 0,
    /** Lifetime count of all completed wake cycles, for the ratio display. */
    val totalWakesLifetime: Long = 0L,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Content-only settings composable used inside the persistent Scaffold
 * in [com.rousecontext.app.ui.navigation.AppNavigation].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    state: SettingsState = SettingsState(),
    onIdleTimeoutChanged: (Int) -> Unit = {},
    onDisableTimeoutToggled: (Boolean) -> Unit = {},
    onPostSessionModeChanged: (PostSessionModeOption) -> Unit = {},
    onShowAllMcpMessagesChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeModeOption) -> Unit = {},
    onSecurityCheckIntervalChanged: (SecurityCheckIntervalOption) -> Unit = {},
    onGenerateNewAddress: () -> Unit = {},
    onFixBatteryOptimization: () -> Unit = {},
    onAcknowledgeAlert: () -> Unit = {},
    onReportBug: () -> Unit = {},
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (state.isLoading) {
        LoadingIndicator(modifier = modifier)
        return
    }
    if (state.errorMessage != null) {
        ErrorState(
            message = state.errorMessage,
            modifier = modifier,
            onRetry = onRetry
        )
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))

        // Appearance section
        SectionHeader(stringResource(R.string.screen_settings_section_appearance))
        SettingsSectionCard {
            Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                EnumSettingsDropdown(
                    label = stringResource(R.string.screen_settings_label_theme),
                    selected = state.themeMode,
                    options = ThemeModeOption.entries,
                    labelFor = { stringResource(it.labelRes()) },
                    onSelected = onThemeModeChanged
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // Connection section
        SectionHeader(stringResource(R.string.screen_settings_section_connection))
        SettingsSectionCard {
            Column(
                modifier = Modifier.padding(
                    start = dimensionResource(R.dimen.spacing_lg),
                    end = dimensionResource(R.dimen.spacing_lg),
                    top = dimensionResource(R.dimen.spacing_lg)
                )
            ) {
                SettingsDropdown(
                    label = stringResource(R.string.screen_settings_label_idle_timeout),
                    selected = stringResource(
                        R.string.screen_settings_idle_timeout_value,
                        state.idleTimeoutMinutes
                    ),
                    options = listOf("2 min", "5 min", "10 min"),
                    onSelected = { value ->
                        val minutes = value.replace(" min", "").toIntOrNull() ?: 5
                        onIdleTimeoutChanged(minutes)
                    }
                )
            }
            SwitchRow(
                title = stringResource(R.string.screen_settings_disable_timeout_title),
                subtitle = if (!state.batteryOptimizationExempt) {
                    stringResource(
                        R.string.screen_settings_disable_timeout_requires_battery
                    )
                } else {
                    null
                },
                checked = state.idleTimeoutDisabled,
                onCheckedChange = onDisableTimeoutToggled,
                enabled = state.batteryOptimizationExempt
            )
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // Audit notifications section
        SectionHeader(stringResource(R.string.screen_settings_section_audit_notifications))
        SettingsSectionCard {
            Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                Text(
                    text = stringResource(R.string.screen_settings_audit_tool_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                EnumSettingsDropdown(
                    label = stringResource(R.string.screen_settings_label_after_session),
                    selected = state.postSessionMode,
                    options = PostSessionModeOption.entries,
                    labelFor = { stringResource(it.labelRes()) },
                    onSelected = onPostSessionModeChanged
                )
            }
            SwitchRow(
                title = stringResource(R.string.screen_settings_show_all_mcp_title),
                subtitle = stringResource(R.string.screen_settings_show_all_mcp_subtitle),
                checked = state.showAllMcpMessages,
                onCheckedChange = onShowAllMcpMessagesChanged
            )
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // Security section
        SectionHeader(stringResource(R.string.screen_settings_section_security))
        SettingsSectionCard {
            Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                EnumSettingsDropdown(
                    label = stringResource(R.string.screen_settings_label_check_interval),
                    selected = state.securityCheckInterval,
                    options = SecurityCheckIntervalOption.entries,
                    labelFor = {
                        stringResource(R.string.screen_settings_check_interval_value, it.hours)
                    },
                    onSelected = onSecurityCheckIntervalChanged
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.screen_settings_generate_new_address),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = state.rotationCooldownMessage
                                ?: stringResource(
                                    R.string.screen_settings_rotate_changes_immediately
                                ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = onGenerateNewAddress,
                        enabled = state.canRotateAddress,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.screen_settings_rotate_button))
                    }
                }
            }
        }

        // Battery warning
        if (state.showBatteryWarning) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            val ext = LocalExtendedColors.current
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = ext.warningContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = ext.warningAccent
                    )
                    Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.screen_settings_battery_optimization_title),
                            style = MaterialTheme.typography.bodyLarge,
                            color = ext.warningAccent
                        )
                        Text(
                            stringResource(
                                R.string.screen_settings_battery_optimization_subtitle
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = ext.onWarningContainer
                        )
                    }
                    OutlinedButton(
                        onClick = onFixBatteryOptimization,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ext.warningAccent
                        ),
                        border = BorderStroke(1.dp, ext.warningAccent)
                    ) {
                        Text(stringResource(R.string.screen_settings_battery_fix_this))
                    }
                }
            }
        }

        // Diagnostics section — only shown once at least one spurious wake has
        // been observed. Read-only, purely informational.
        if (state.spuriousWakesLast24h > 0) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            SectionHeader(stringResource(R.string.screen_settings_section_diagnostics))
            SettingsSectionCard {
                Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                    Text(
                        stringResource(
                            R.string.screen_settings_spurious_wakes,
                            state.spuriousWakesLast24h,
                            state.totalWakesLifetime
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
                    Text(
                        stringResource(R.string.screen_settings_spurious_wakes_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Trust Status section
        if (state.trustStatus != null) {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            SectionHeader(stringResource(R.string.screen_settings_section_trust_status))
            TrustStatusSection(
                trustStatus = state.trustStatus,
                onAcknowledgeAlert = onAcknowledgeAlert
            )
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // About section
        SectionHeader(stringResource(R.string.screen_settings_section_about))
        SettingsSectionCard {
            Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                Text(
                    stringResource(R.string.screen_settings_version, state.versionName),
                    style = MaterialTheme.typography.bodyMedium
                )
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = dimensionResource(R.dimen.spacing_sm)),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                Text(
                    stringResource(R.string.screen_settings_license),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))

        // Support section
        SectionHeader(stringResource(R.string.screen_settings_section_support))
        SettingsSectionCard {
            Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
                Text(
                    text = stringResource(R.string.screen_settings_support_privacy_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onReportBug),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.screen_settings_report_bug),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.screen_settings_report_bug_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xl)))
    }
}

/**
 * Full-screen settings with its own Scaffold, used by previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsState = SettingsState(),
    onIdleTimeoutChanged: (Int) -> Unit = {},
    onDisableTimeoutToggled: (Boolean) -> Unit = {},
    onPostSessionModeChanged: (PostSessionModeOption) -> Unit = {},
    onShowAllMcpMessagesChanged: (Boolean) -> Unit = {},
    onThemeModeChanged: (ThemeModeOption) -> Unit = {},
    onSecurityCheckIntervalChanged: (SecurityCheckIntervalOption) -> Unit = {},
    onGenerateNewAddress: () -> Unit = {},
    onFixBatteryOptimization: () -> Unit = {},
    onAcknowledgeAlert: () -> Unit = {},
    onReportBug: () -> Unit = {},
    onRetry: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_settings_title)) },
                colors = appBarColors()
            )
        },
        bottomBar = {
            NavigationBar(containerColor = navBarContainerColor()) {
                val itemColors = navBarItemColors()
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(0) },
                    icon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = stringResource(
                                R.string.screen_settings_nav_home
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_settings_nav_home)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = false,
                    onClick = { onTabSelected(1) },
                    icon = {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(
                                R.string.screen_settings_nav_audit
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_settings_nav_audit)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = true,
                    onClick = { onTabSelected(2) },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(
                                R.string.screen_settings_nav_settings
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_settings_nav_settings)) },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        SettingsContent(
            state = state,
            onIdleTimeoutChanged = onIdleTimeoutChanged,
            onDisableTimeoutToggled = onDisableTimeoutToggled,
            onPostSessionModeChanged = onPostSessionModeChanged,
            onShowAllMcpMessagesChanged = onShowAllMcpMessagesChanged,
            onThemeModeChanged = onThemeModeChanged,
            onSecurityCheckIntervalChanged = onSecurityCheckIntervalChanged,
            onGenerateNewAddress = onGenerateNewAddress,
            onFixBatteryOptimization = onFixBatteryOptimization,
            onAcknowledgeAlert = onAcknowledgeAlert,
            onReportBug = onReportBug,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        )
    }
}

private const val FINGERPRINT_TRUNCATE_LENGTH = 23

private const val SECURITY_DOCS_BASE = "https://rousecontext.com/security"

@Composable
internal fun TrustStatusSection(
    trustStatus: TrustStatusState,
    onAcknowledgeAlert: () -> Unit = {}
) {
    val ext = LocalExtendedColors.current
    val (statusIcon, statusColor, statusLabel) = when (trustStatus.overallStatus) {
        TrustOverallStatus.VERIFIED -> Triple(
            Icons.Default.CheckCircle,
            SuccessGreen,
            stringResource(R.string.screen_settings_trust_verified)
        )
        TrustOverallStatus.WARNING -> Triple(
            Icons.Default.Warning,
            ext.warningAccent,
            stringResource(R.string.screen_settings_trust_warning)
        )
        TrustOverallStatus.ALERT -> Triple(
            Icons.Default.Error,
            ext.alertContent,
            stringResource(R.string.screen_settings_trust_alert)
        )
    }

    val timeAgo = formatTimeAgo(trustStatus.lastCheckTime)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (trustStatus.overallStatus == TrustOverallStatus.ALERT) {
            CardDefaults.cardColors(containerColor = ext.alertContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg))) {
            // Overall status row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    statusIcon,
                    contentDescription = statusLabel,
                    tint = statusColor,
                    modifier = Modifier.size(dimensionResource(R.dimen.spacing_xl))
                )
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = statusColor
                )
            }

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))

            // Self-check row
            TrustCheckRow(
                label = stringResource(R.string.screen_settings_trust_self_check),
                result = trustStatus.selfCheckResult,
                timeAgo = timeAgo
            )

            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))

            // CT log row
            TrustCheckRow(
                label = stringResource(R.string.screen_settings_trust_ct_log),
                result = trustStatus.ctCheckResult,
                timeAgo = timeAgo
            )

            // Cert fingerprint
            if (trustStatus.certFingerprint.isNotEmpty()) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                CertFingerprintRow(trustStatus.certFingerprint)
            }

            // Acknowledge button when alert is active
            if (trustStatus.overallStatus == TrustOverallStatus.ALERT) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                Text(
                    text = stringResource(R.string.screen_settings_trust_alert_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
                OutlinedButton(
                    onClick = onAcknowledgeAlert,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = statusColor
                    ),
                    border = BorderStroke(1.dp, statusColor)
                ) {
                    Text(stringResource(R.string.screen_settings_trust_acknowledge))
                }
            }

            // "Learn what this means" link to security docs
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
            val context = LocalContext.current
            val docsUrl = trustStatusDocsUrl(trustStatus)
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(docsUrl))
                    )
                }
            ) {
                Text(
                    text = stringResource(R.string.screen_settings_trust_learn_more),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/**
 * Returns the security docs URL, optionally deep-linking to a specific section
 * based on which check is in a non-verified state.
 */
private fun trustStatusDocsUrl(trustStatus: TrustStatusState): String {
    val anchor = when {
        trustStatus.selfCheckResult == "alert" -> "#self-cert-verification-failed"
        trustStatus.ctCheckResult == "alert" -> "#ct-log-check-failed"
        trustStatus.selfCheckResult == "warning" -> "#self-cert-verification-failed"
        trustStatus.ctCheckResult == "warning" -> "#ct-log-check-failed"
        else -> ""
    }
    return "$SECURITY_DOCS_BASE$anchor"
}

@Composable
private fun TrustCheckRow(label: String, result: String, timeAgo: String) {
    val ext = LocalExtendedColors.current
    val (icon, color, displayResult) = when (result) {
        "verified" -> Triple(
            Icons.Default.CheckCircle,
            SuccessGreen,
            stringResource(R.string.screen_settings_trust_result_verified)
        )
        "warning" -> Triple(
            Icons.Default.Warning,
            ext.warningAccent,
            stringResource(R.string.screen_settings_trust_result_warning)
        )
        "alert" -> Triple(
            Icons.Default.Error,
            ext.alertContent,
            stringResource(R.string.screen_settings_trust_result_alert)
        )
        // Issue #228: "skipped" is the pre-onboarding / not-yet-configured
        // state. Rendered in muted colours so it does not read like a failure.
        "skipped" -> Triple(
            Icons.Default.Info,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.screen_settings_trust_result_skipped)
        )
        else -> Triple(
            Icons.Default.Warning,
            MaterialTheme.colorScheme.onSurfaceVariant,
            stringResource(R.string.screen_settings_trust_result_unchecked)
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(dimensionResource(R.dimen.spacing_lg))
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
        Text(
            text = stringResource(R.string.screen_settings_trust_row, label, displayResult),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f)
        )
        if (timeAgo.isNotEmpty()) {
            Text(
                text = timeAgo,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CertFingerprintRow(fingerprint: String) {
    var expanded by remember { mutableStateOf(false) }
    val displayFingerprint = if (expanded || fingerprint.length <= FINGERPRINT_TRUNCATE_LENGTH) {
        fingerprint
    } else {
        fingerprint.take(FINGERPRINT_TRUNCATE_LENGTH) + "..."
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Text(
            text = stringResource(R.string.screen_settings_cert_fingerprint_label),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = displayFingerprint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun formatTimeAgo(epochMillis: Long): String {
    if (epochMillis == 0L) return ""
    val now = System.currentTimeMillis()
    val diffMinutes = (now - epochMillis) / 60_000
    return when {
        diffMinutes < 1 -> stringResource(R.string.screen_settings_time_just_now)
        diffMinutes < 60 -> stringResource(
            R.string.screen_settings_time_min_ago,
            diffMinutes
        )
        diffMinutes < 1440 -> stringResource(
            R.string.screen_settings_time_hours_ago,
            diffMinutes / 60
        )
        else -> stringResource(
            R.string.screen_settings_time_days_ago,
            diffMinutes / 1440
        )
    }
}

@Composable
private fun SettingsSectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        content()
    }
}

/**
 * Typed dropdown: each option is an arbitrary value of type [T] and the
 * display label is resolved via [labelFor]. Keeps the selected state typed
 * so the caller never has to string-match on the result. Used for the
 * theme/post-session/check-interval dropdowns whose options are enums.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> EnumSettingsDropdown(
    label: String,
    selected: T,
    options: List<T>,
    labelFor: @Composable (T) -> String,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = labelFor(selected),
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .width(150.dp)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    val optionLabel = labelFor(option)
                    DropdownMenuItem(
                        text = { Text(optionLabel) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsDropdown(
    label: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selected,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier
                    .width(150.dp)
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = {
                            onSelected(option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingsScreen()
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun SettingsNoBatteryWarningPreview() {
    RouseContextTheme(darkTheme = true) {
        SettingsScreen(
            state = SettingsState(
                showBatteryWarning = false,
                batteryOptimizationExempt = true
            )
        )
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun SettingsLightPreview() {
    RouseContextTheme(darkTheme = false) {
        SettingsScreen()
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun SettingsTrustStatusLightPreview() {
    RouseContextTheme(darkTheme = false) {
        SettingsScreen(
            state = SettingsState(
                trustStatus = TrustStatusState(
                    lastCheckTime = System.currentTimeMillis() - 300_000,
                    selfCheckResult = "verified",
                    ctCheckResult = "warning",
                    certFingerprint = "SHA256:AB:CD:EF:12:34:56:78:90",
                    overallStatus = TrustOverallStatus.WARNING
                )
            )
        )
    }
}
