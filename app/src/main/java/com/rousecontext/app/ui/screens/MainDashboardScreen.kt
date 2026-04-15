package com.rousecontext.app.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rousecontext.app.BuildConfig
import com.rousecontext.app.R
import com.rousecontext.app.ui.components.ErrorState
import com.rousecontext.app.ui.components.ListDivider
import com.rousecontext.app.ui.components.ListRow
import com.rousecontext.app.ui.components.LoadingIndicator
import com.rousecontext.app.ui.components.SectionHeader
import com.rousecontext.app.ui.components.appBarColors
import com.rousecontext.app.ui.components.navBarContainerColor
import com.rousecontext.app.ui.components.navBarItemColors
import com.rousecontext.app.ui.theme.RouseContextTheme

enum class ConnectionStatus { CONNECTED, DISCONNECTED }

enum class IntegrationStatus { ACTIVE, PENDING }

@Immutable
data class IntegrationItem(
    val id: String,
    val name: String,
    val status: IntegrationStatus,
    val url: String
)

@Immutable
data class AuditEntry(
    val time: String,
    val toolName: String,
    val durationMs: Long,
    val id: Long = 0
)

enum class TerminalReason { KeyGenerationFailed, CnMismatch }

sealed interface CertBanner {
    data object Renewing : CertBanner
    data class Expired(val renewalInProgress: Boolean) : CertBanner
    data class RateLimited(val retryDate: String) : CertBanner
    data class Onboarding(
        val generatingKeysDone: Boolean,
        val registeringDone: Boolean,
        val issuingCert: Boolean
    ) : CertBanner

    /**
     * Terminal outcome of the cert-renewal worker. The condition is not actionable from
     * within the app (wrong CN means the relay issued an unexpected cert; key-gen failure
     * means keystore is in a bad state). The banner is informational only, surfacing the
     * problem so the user knows the device can't re-establish a secure connection.
     */
    data class TerminalFailure(val reason: TerminalReason) : CertBanner
}

/**
 * Displayed when the user has denied (or never granted) permission to post
 * notifications. Tapping the banner opens system app-notification settings
 * so the user can re-enable. Distinct from [CertBanner] because the concern
 * is unrelated to certificate lifecycle and the two can co-exist.
 */
data object NotificationBanner

/**
 * Low-severity informational banner surfaced when the relay appears to be
 * issuing spurious wake notifications (recurring wakes that never result in
 * a client actually opening a stream). Non-actionable from the app side; the
 * banner taps through to Settings where the user sees the ratio and can
 * decide if they want to report the issue.
 */
data class SpuriousWakeBanner(val rolling24hCount: Int)

private const val MAX_RECENT_ITEMS = 3

@Immutable
data class DashboardState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val activeSessionCount: Int = 0,
    val integrations: List<IntegrationItem> = emptyList(),
    val recentActivity: List<AuditEntry> = emptyList(),
    val certBanner: CertBanner? = null,
    val notificationBanner: NotificationBanner? = null,
    val spuriousWakeBanner: SpuriousWakeBanner? = null,
    val hasMoreIntegrationsToAdd: Boolean = true,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Content-only dashboard composable used inside the persistent Scaffold
 * in [com.rousecontext.app.ui.navigation.AppNavigation].
 */
@Composable
fun HomeDashboardContent(
    state: DashboardState = DashboardState(),
    onAddIntegration: () -> Unit = {},
    onIntegrationClick: (String) -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onRetryRenewal: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = dimensionResource(R.dimen.spacing_lg))
    ) {
        dashboardBannerItems(
            state = state,
            onRetryRenewal = onRetryRenewal,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenSettings = onOpenSettings
        )

        // Integrations header
        item {
            SectionHeader(stringResource(R.string.screen_dashboard_section_integrations))
        }

        // Empty state or integration list
        if (state.integrations.isEmpty()) {
            item {
                EmptyIntegrationsCard(onAddIntegration)
            }
        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        state.integrations.forEachIndexed { index, integration ->
                            IntegrationRow(
                                integration = integration,
                                onClick = { onIntegrationClick(integration.id) }
                            )
                            if (index < state.integrations.lastIndex) {
                                ListDivider()
                            }
                        }
                    }
                }
                if (state.hasMoreIntegrationsToAdd) {
                    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
                    AddIntegrationCard(onAddIntegration)
                }
            }
        }

        // Recent activity
        item {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            SectionHeader(stringResource(R.string.screen_dashboard_section_recent_activity))
        }

        if (state.recentActivity.isEmpty()) {
            item {
                EmptyRecentActivityCard()
            }
        } else {
            val visibleActivity = state.recentActivity.take(MAX_RECENT_ITEMS)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        visibleActivity.forEachIndexed { index, entry ->
                            ActivityRow(entry)
                            if (index < visibleActivity.lastIndex) {
                                ListDivider()
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onViewAllActivity) {
                        Text(stringResource(R.string.screen_dashboard_view_all))
                    }
                }
            }
        }
    }
}

/**
 * Full-screen dashboard with its own Scaffold, used by previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboardScreen(
    state: DashboardState = DashboardState(),
    selectedTab: Int = 0,
    onAddIntegration: () -> Unit = {},
    onIntegrationClick: (String) -> Unit = {},
    onViewAllActivity: () -> Unit = {},
    onRetryRenewal: () -> Unit = {},
    onOpenNotificationSettings: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onRetry: () -> Unit = {},
    onTabSelected: (Int) -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_dashboard_title)) },
                colors = appBarColors()
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = navBarContainerColor()
            ) {
                val itemColors = navBarItemColors()
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { onTabSelected(0) },
                    icon = {
                        Icon(
                            Icons.Default.Home,
                            contentDescription = stringResource(
                                R.string.screen_dashboard_nav_home
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_dashboard_nav_home)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { onTabSelected(1) },
                    icon = {
                        Icon(
                            Icons.Default.History,
                            contentDescription = stringResource(
                                R.string.screen_dashboard_nav_audit
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_dashboard_nav_audit)) },
                    colors = itemColors
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { onTabSelected(2) },
                    icon = {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(
                                R.string.screen_dashboard_nav_settings
                            )
                        )
                    },
                    label = { Text(stringResource(R.string.screen_dashboard_nav_settings)) },
                    colors = itemColors
                )
            }
        }
    ) { padding ->
        HomeDashboardContent(
            state = state,
            onAddIntegration = onAddIntegration,
            onIntegrationClick = onIntegrationClick,
            onViewAllActivity = onViewAllActivity,
            onRetryRenewal = onRetryRenewal,
            onOpenNotificationSettings = onOpenNotificationSettings,
            onOpenSettings = onOpenSettings,
            onRetry = onRetry,
            modifier = Modifier.padding(padding)
        )
    }
}

@Composable
private fun ConnectionStatusRow(status: ConnectionStatus, sessionCount: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.spacing_md)),
            tint = when (status) {
                ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
                ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
            }
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
        Text(
            text = when (status) {
                ConnectionStatus.CONNECTED ->
                    if (sessionCount == 1) {
                        stringResource(R.string.screen_dashboard_connected_one, sessionCount)
                    } else {
                        stringResource(R.string.screen_dashboard_connected_many, sessionCount)
                    }
                ConnectionStatus.DISCONNECTED ->
                    stringResource(R.string.screen_dashboard_disconnected)
            },
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun NotificationBannerCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg)),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.NotificationsOff,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.screen_dashboard_notifications_disabled_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.screen_dashboard_notifications_disabled_subtitle
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

private fun LazyListScope.dashboardBannerItems(
    state: DashboardState,
    onRetryRenewal: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    // Cert banner (most urgent — show first).
    state.certBanner?.let { banner ->
        item {
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            CertBannerCard(banner, onRetryRenewal)
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        }
    }

    // Notification permission banner (independent of cert state).
    if (state.notificationBanner != null) {
        item {
            if (state.certBanner == null) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            }
            NotificationBannerCard(onClick = onOpenNotificationSettings)
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        }
    }

    // Spurious wake banner (informational; only when rolling 24h count is high).
    state.spuriousWakeBanner?.let { banner ->
        item {
            if (state.certBanner == null && state.notificationBanner == null) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            }
            SpuriousWakeBannerCard(banner, onClick = onOpenSettings)
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        }
    }

    // Connection status (debug builds only).
    if (BuildConfig.DEBUG) {
        item {
            if (state.certBanner == null) {
                Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            }
            ConnectionStatusRow(state.connectionStatus, state.activeSessionCount)
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
        }
    }
}

@Composable
private fun SpuriousWakeBannerCard(banner: SpuriousWakeBanner, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg)),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = Icons.Default.Schedule,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.screen_dashboard_spurious_wake_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(
                        R.string.screen_dashboard_spurious_wake_subtitle,
                        banner.rolling24hCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CertBannerCard(banner: CertBanner, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bannerContainerColor(banner))
    ) {
        Row(
            modifier = Modifier.padding(dimensionResource(R.dimen.spacing_lg)),
            verticalAlignment = Alignment.Top
        ) {
            BannerIcon(banner)
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
            Column(modifier = Modifier.weight(1f)) {
                BannerBody(banner, onRetry)
            }
        }
    }
}

@Composable
private fun bannerContainerColor(banner: CertBanner) = when (banner) {
    is CertBanner.Expired -> if (!banner.renewalInProgress) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    is CertBanner.TerminalFailure -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.secondaryContainer
}

@Composable
private fun BannerIcon(banner: CertBanner) {
    when (banner) {
        is CertBanner.Renewing -> Icon(
            Icons.Default.Sync,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm))
        )
        is CertBanner.Expired -> if (banner.renewalInProgress) {
            Icon(
                Icons.Default.Sync,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm))
            )
        } else {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
        }
        is CertBanner.RateLimited -> Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm))
        )
        is CertBanner.Onboarding -> Icon(
            Icons.Default.Build,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm))
        )
        is CertBanner.TerminalFailure -> Icon(
            Icons.Default.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
            tint = MaterialTheme.colorScheme.onErrorContainer
        )
    }
}

@Composable
private fun BannerBody(banner: CertBanner, onRetry: () -> Unit) {
    when (banner) {
        is CertBanner.Renewing -> RenewingBody()
        is CertBanner.Expired -> ExpiredBody(banner, onRetry)
        is CertBanner.RateLimited -> RateLimitedBody(banner)
        is CertBanner.Onboarding -> OnboardingBody(banner)
        is CertBanner.TerminalFailure -> TerminalFailureBody(banner)
    }
}

@Composable
private fun RenewingBody() {
    Text(
        stringResource(R.string.screen_dashboard_cert_renewing_title),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        stringResource(R.string.screen_dashboard_cert_renewing_subtitle),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun ExpiredBody(banner: CertBanner.Expired, onRetry: () -> Unit) {
    if (banner.renewalInProgress) {
        Text(
            stringResource(R.string.screen_dashboard_cert_expired_renewing_title),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.screen_dashboard_cert_expired_renewing_subtitle),
            style = MaterialTheme.typography.bodySmall
        )
    } else {
        Text(
            stringResource(R.string.screen_dashboard_cert_expired_title),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            stringResource(R.string.screen_dashboard_cert_expired_subtitle),
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_md)))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.onErrorContainer,
                contentColor = MaterialTheme.colorScheme.errorContainer
            )
        ) { Text(stringResource(R.string.common_retry)) }
    }
}

@Composable
private fun RateLimitedBody(banner: CertBanner.RateLimited) {
    Text(
        stringResource(R.string.screen_dashboard_cert_rate_limited_title),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        stringResource(R.string.screen_dashboard_cert_rate_limited_subtitle, banner.retryDate),
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun OnboardingBody(banner: CertBanner.Onboarding) {
    Text(
        stringResource(R.string.screen_dashboard_cert_onboarding_title),
        style = MaterialTheme.typography.titleSmall
    )
    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
    OnboardingStep(
        stringResource(R.string.screen_dashboard_cert_onboarding_step_keys),
        banner.generatingKeysDone
    )
    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
    OnboardingStep(
        stringResource(R.string.screen_dashboard_cert_onboarding_step_registering),
        banner.registeringDone
    )
    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
    OnboardingStep(
        stringResource(R.string.screen_dashboard_cert_onboarding_step_issuing),
        done = false,
        active = banner.issuingCert
    )
}

@Composable
private fun TerminalFailureBody(banner: CertBanner.TerminalFailure) {
    Text(
        stringResource(R.string.screen_dashboard_cert_terminal_title),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onErrorContainer
    )
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        stringResource(R.string.screen_dashboard_cert_terminal_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer
    )
    Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
    Text(
        text = when (banner.reason) {
            TerminalReason.KeyGenerationFailed ->
                stringResource(R.string.screen_dashboard_cert_terminal_key_gen)
            TerminalReason.CnMismatch ->
                stringResource(R.string.screen_dashboard_cert_terminal_cn_mismatch)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onErrorContainer
    )
}

@Composable
private fun OnboardingStep(label: String, done: Boolean, active: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            done -> Icon(
                Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.spacing_sm)),
                tint = MaterialTheme.colorScheme.primary
            )
            active -> CircularProgressIndicator(
                modifier = Modifier.size(10.dp),
                strokeWidth = 2.dp
            )
            else -> Icon(
                Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.spacing_sm)),
                tint = MaterialTheme.colorScheme.outline
            )
        }
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (done) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else if (active) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun EmptyIntegrationsCard(onAdd: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.spacing_xl)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.screen_dashboard_empty_integrations_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_xs)))
            Text(
                text = stringResource(R.string.screen_dashboard_empty_integrations_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_lg)))
            Button(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
                Text(stringResource(R.string.screen_dashboard_empty_add_first))
            }
        }
    }
}

@Composable
private fun IntegrationRow(integration: IntegrationItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(dimensionResource(R.dimen.spacing_lg)),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Default.Circle,
                contentDescription = null,
                modifier = Modifier.size(10.dp),
                tint = when (integration.status) {
                    IntegrationStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                    IntegrationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                }
            )
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
            Column {
                Text(integration.name, style = MaterialTheme.typography.bodyLarge)
                if (integration.status == IntegrationStatus.PENDING) {
                    Text(
                        stringResource(R.string.screen_dashboard_integration_pending_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = when (integration.status) {
                IntegrationStatus.ACTIVE ->
                    stringResource(R.string.screen_dashboard_integration_active)
                IntegrationStatus.PENDING ->
                    stringResource(R.string.screen_dashboard_integration_pending)
            },
            style = MaterialTheme.typography.labelMedium,
            color = when (integration.status) {
                IntegrationStatus.ACTIVE -> MaterialTheme.colorScheme.primary
                IntegrationStatus.PENDING -> MaterialTheme.colorScheme.tertiary
            }
        )
    }
}

@Composable
private fun EmptyRecentActivityCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.spacing_xl)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.spacing_xxl)),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(dimensionResource(R.dimen.spacing_sm)))
            Text(
                text = stringResource(R.string.screen_dashboard_empty_recent_activity),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ActivityRow(entry: AuditEntry) {
    ListRow {
        Text(
            text = entry.toolName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = entry.time,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_md)))
        DurationText(entry.durationMs)
    }
}

@Composable
fun formatDuration(ms: Long): String = if (ms >= 1000) {
    stringResource(R.string.screen_dashboard_duration_seconds, ms / 1000.0)
} else {
    stringResource(R.string.screen_dashboard_duration_ms, ms)
}

@Composable
fun DurationText(durationMs: Long) {
    Text(
        text = formatDuration(durationMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(48.dp),
        textAlign = TextAlign.End
    )
}

@Composable
private fun AddIntegrationCard(onAddIntegration: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onAddIntegration),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(dimensionResource(R.dimen.spacing_lg)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(dimensionResource(R.dimen.icon_size_sm)),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(dimensionResource(R.dimen.spacing_sm)))
            Text(
                text = stringResource(R.string.screen_dashboard_add_integration),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

// --- Previews ---

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardEmptyPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(state = DashboardState())
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardWithIntegrationsPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                connectionStatus = ConnectionStatus.CONNECTED,
                activeSessionCount = 1,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.my-device.rousecontext.com/health/mcp"
                    ),
                    IntegrationItem(
                        id = "notifications",
                        name = "Notifications",
                        status = IntegrationStatus.PENDING,
                        url = "https://brave-falcon.my-device.rousecontext.com/notifications/mcp"
                    )
                ),
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "health/get_steps", 142),
                    AuditEntry("10:31 AM", "health/get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "health/get_heart_rate", 1250)
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertExpiredPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Expired(renewalInProgress = false),
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.my-device.rousecontext.com/health/mcp"
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertRenewingPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.Renewing,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.my-device.rousecontext.com/health/mcp"
                    )
                )
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertRateLimitedPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.RateLimited(retryDate = "Apr 11")
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertOnboardingPreview() {
    RouseContextTheme(darkTheme = true) {
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
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertTerminalKeyGenPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.TerminalFailure(TerminalReason.KeyGenerationFailed)
            )
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun DashboardCertTerminalCnMismatchPreview() {
    RouseContextTheme(darkTheme = true) {
        MainDashboardScreen(
            state = DashboardState(
                certBanner = CertBanner.TerminalFailure(TerminalReason.CnMismatch)
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
fun DashboardEmptyLightPreview() {
    RouseContextTheme(darkTheme = false) {
        MainDashboardScreen(state = DashboardState())
    }
}

@Preview(
    showBackground = true,
    showSystemUi = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO
)
@Composable
fun DashboardWithIntegrationsLightPreview() {
    RouseContextTheme(darkTheme = false) {
        MainDashboardScreen(
            state = DashboardState(
                connectionStatus = ConnectionStatus.CONNECTED,
                activeSessionCount = 1,
                integrations = listOf(
                    IntegrationItem(
                        id = "health",
                        name = "Health Connect",
                        status = IntegrationStatus.ACTIVE,
                        url = "https://brave-falcon.my-device.rousecontext.com/health/mcp"
                    ),
                    IntegrationItem(
                        id = "notifications",
                        name = "Notifications",
                        status = IntegrationStatus.PENDING,
                        url = "https://brave-falcon.my-device.rousecontext.com/notifications/mcp"
                    )
                ),
                recentActivity = listOf(
                    AuditEntry("10:32 AM", "health/get_steps", 142),
                    AuditEntry("10:31 AM", "health/get_sleep", 89),
                    AuditEntry("Apr 7, 3:15 PM", "health/get_heart_rate", 1250)
                )
            )
        )
    }
}
