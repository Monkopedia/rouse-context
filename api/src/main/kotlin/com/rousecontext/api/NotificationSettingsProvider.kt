package com.rousecontext.api

import kotlinx.coroutines.flow.Flow

/**
 * Provides notification preference access for the notification model.
 *
 * The app implements this backed by Preferences DataStore. The notification
 * module reads it to decide what notifications to post after sessions.
 */
interface NotificationSettingsProvider {

    /** Current notification settings. */
    val settings: NotificationSettings

    /**
     * Reactive view of the settings. Emits the current value on collection and
     * every subsequent change. Used by UI layers (Settings toggle, audit
     * history view) to react without polling.
     */
    fun observeSettings(): Flow<NotificationSettings>

    /**
     * Persist a new post-session notification mode. Called from the
     * onboarding NotificationPreferences screen and from the global
     * Settings screen when the user picks a different option.
     */
    suspend fun setPostSessionMode(mode: PostSessionMode)

    /**
     * Persist whether the audit history UI should show every MCP JSON-RPC
     * message (not just tool calls). See issue #105.
     */
    suspend fun setShowAllMcpMessages(enabled: Boolean)
}

/**
 * User preferences that control notification behavior.
 */
data class NotificationSettings(
    /** How post-session notifications are handled. */
    val postSessionMode: PostSessionMode,

    /** Whether the notification permission has been granted (Android 13+). */
    val notificationPermissionGranted: Boolean,

    /**
     * Whether the audit history UI should show every MCP JSON-RPC message,
     * not just tool calls. Default false. See issue #105.
     */
    val showAllMcpMessages: Boolean = false
)

/**
 * Controls what happens after an MCP session ends.
 */
enum class PostSessionMode {
    /** Post a single summary notification after session ends. */
    SUMMARY,

    /** Post a notification for each tool usage during the session. */
    EACH_USAGE,

    /** Suppress post-session notifications entirely. */
    SUPPRESS
}
