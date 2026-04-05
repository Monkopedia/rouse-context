package com.rousecontext.notifications

/**
 * User-configurable notification preferences.
 *
 * @property mode Controls which notifications are shown beyond the required foreground notification.
 * @property permissionGranted Whether the POST_NOTIFICATIONS permission is granted (Android 13+).
 */
data class NotificationSettings(
    val mode: NotificationMode = NotificationMode.Summary,
    val permissionGranted: Boolean = true,
)

/**
 * Notification verbosity modes.
 */
enum class NotificationMode {
    /** Post a summary notification after each session disconnect. */
    Summary,

    /** Post a notification for every individual tool call. */
    Every,

    /** Suppress all optional notifications; only show the required foreground notification. */
    Suppress,
}
