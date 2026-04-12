package com.rousecontext.api

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
     * Persist a new post-session notification mode. Called from the
     * onboarding NotificationPreferences screen and from the global
     * Settings screen when the user picks a different option.
     */
    suspend fun setPostSessionMode(mode: PostSessionMode)
}

/**
 * User preferences that control notification behavior.
 */
data class NotificationSettings(
    /** How post-session notifications are handled. */
    val postSessionMode: PostSessionMode,

    /** Whether the notification permission has been granted (Android 13+). */
    val notificationPermissionGranted: Boolean
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
