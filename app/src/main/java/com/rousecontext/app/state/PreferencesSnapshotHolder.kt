package com.rousecontext.app.state

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.api.McpIntegration
import com.rousecontext.api.NotificationSettings
import com.rousecontext.api.NotificationSettingsProvider
import com.rousecontext.api.PostSessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Typed value for a generic integration setting. */
sealed class PreferenceValue {
    data class BoolValue(val value: Boolean) : PreferenceValue()
    data class IntValue(val value: Int) : PreferenceValue()
}

/**
 * A reactive snapshot of every preference the UI cares about. Collected lazily
 * while any UI subscriber is active (see [PreferencesSnapshotHolder]).
 */
data class PreferencesSnapshot(
    val integrationEnabled: Map<String, Boolean>,
    val integrationEverEnabled: Map<String, Boolean>,
    val integrationSettings: Map<Pair<String, String>, PreferenceValue>,
    val notificationSettings: NotificationSettings
) {
    companion object {
        fun default(): PreferencesSnapshot = PreferencesSnapshot(
            integrationEnabled = emptyMap(),
            integrationEverEnabled = emptyMap(),
            integrationSettings = emptyMap(),
            notificationSettings = NotificationSettings(
                postSessionMode = PostSessionMode.SUMMARY,
                notificationPermissionGranted = false,
                showAllMcpMessages = false
            )
        )
    }
}

/**
 * Live, reactive view of every preference Compose screens read. Replaces the
 * old synchronous getters that were forced to [kotlinx.coroutines.runBlocking]
 * inside DataStore-backed stores.
 *
 * Collection is started [SharingStarted.WhileSubscribed]: the underlying flows
 * are only active while a Compose screen (or a test) is observing.
 *
 * Writes still go directly through each store's suspending setter; this holder
 * only provides a snapshot for reads.
 */
class PreferencesSnapshotHolder(
    integrationStateStore: IntegrationStateStore,
    integrationSettingsStore: IntegrationSettingsStore,
    notificationSettingsProvider: NotificationSettingsProvider,
    private val integrations: List<McpIntegration>,
    appScope: CoroutineScope
) {

    private val settingKeysByIntegration: Map<String, List<Pair<String, PreferenceType>>> = mapOf(
        "notifications" to listOf(
            IntegrationSettingsStore.KEY_RETENTION_DAYS to PreferenceType.Int(
                NOTIFICATION_DEFAULT_RETENTION
            ),
            IntegrationSettingsStore.KEY_ALLOW_ACTIONS to PreferenceType.Bool(false)
        ),
        "outreach" to listOf(
            IntegrationSettingsStore.KEY_DND_TOGGLED to PreferenceType.Bool(false),
            IntegrationSettingsStore.KEY_DIRECT_LAUNCH_ENABLED to PreferenceType.Bool(false)
        )
    )

    private val enabledMapFlow: Flow<Map<String, Boolean>> =
        combineIntegrationBooleans { id -> integrationStateStore.observeUserEnabled(id) }

    private val everEnabledMapFlow: Flow<Map<String, Boolean>> =
        combineIntegrationBooleans { id -> integrationStateStore.observeEverEnabled(id) }

    private val settingsMapFlow: Flow<Map<Pair<String, String>, PreferenceValue>> = run {
        val settingFlows = settingKeysByIntegration.flatMap { (integrationId, keys) ->
            keys.map { (key, type) ->
                when (type) {
                    is PreferenceType.Bool ->
                        integrationSettingsStore.observeBoolean(integrationId, key, type.default)
                            .map { value ->
                                (integrationId to key) to
                                    PreferenceValue.BoolValue(value) as PreferenceValue
                            }

                    is PreferenceType.Int ->
                        integrationSettingsStore.observeInt(integrationId, key, type.default)
                            .map { value ->
                                (integrationId to key) to
                                    PreferenceValue.IntValue(value) as PreferenceValue
                            }
                }
            }
        }
        if (settingFlows.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyMap())
        } else {
            combine(settingFlows) { it.toMap() }
        }
    }

    val snapshot: StateFlow<PreferencesSnapshot> = combine(
        enabledMapFlow,
        everEnabledMapFlow,
        settingsMapFlow,
        notificationSettingsProvider.observeSettings()
    ) { enabled, ever, settings, notif ->
        PreferencesSnapshot(
            integrationEnabled = enabled,
            integrationEverEnabled = ever,
            integrationSettings = settings,
            notificationSettings = notif
        )
    }.stateIn(
        appScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        PreferencesSnapshot.default()
    )

    private fun combineIntegrationBooleans(
        flowFor: (String) -> Flow<Boolean>
    ): Flow<Map<String, Boolean>> {
        val ids = integrations.map { it.id }
        return if (ids.isEmpty()) {
            kotlinx.coroutines.flow.flowOf(emptyMap())
        } else {
            combine(ids.map { id -> flowFor(id).map { id to it } }) { pairs ->
                pairs.toMap()
            }
        }
    }

    private sealed class PreferenceType {
        data class Bool(val default: Boolean) : PreferenceType()
        data class Int(val default: kotlin.Int) : PreferenceType()
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val NOTIFICATION_DEFAULT_RETENTION = 7
    }
}
