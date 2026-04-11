package com.rousecontext.notifications

/**
 * Actions the notification state machine emits.
 * The [NotificationAdapter] translates these into Android NotificationManager calls.
 */
sealed interface NotificationAction {
    /** Show or update the ongoing foreground service notification. */
    data class ShowForeground(val message: String) : NotificationAction

    /** Post a session summary after disconnect. */
    data class PostSummary(val toolCallCount: Int) : NotificationAction

    /** Post a warning (e.g. disconnect with no tool calls). */
    data class PostWarning(val message: String) : NotificationAction

    /** Post an error notification. */
    data class PostError(val message: String, val streamId: Int? = null) : NotificationAction

    /** Post a per-tool-call usage notification. */
    data class PostToolUsage(val toolName: String, val provider: String) : NotificationAction

    /** Post an informational notification. */
    data class PostInfo(val message: String) : NotificationAction

    /** Post a high-priority security alert. */
    data class PostAlert(val message: String) : NotificationAction
}
