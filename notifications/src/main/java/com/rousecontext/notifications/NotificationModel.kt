package com.rousecontext.notifications

/**
 * Pure-function notification state machine.
 *
 * Given a [SessionEvent] and current [NotificationSettings], returns a list of
 * [NotificationAction]s to execute. Contains no Android framework dependencies.
 */
object NotificationModel {

    /**
     * Compute the notification actions for a given event and settings.
     *
     * The foreground notification is always emitted regardless of mode/permission,
     * because it is required by the Android foreground service contract.
     * All other notifications respect the mode and permission settings.
     */
    fun onEvent(
        event: SessionEvent,
        settings: NotificationSettings,
    ): List<NotificationAction> {
        // Permission denied forces suppress for everything except foreground
        val effectiveMode = if (settings.permissionGranted) {
            settings.mode
        } else {
            NotificationMode.Suppress
        }

        return when (event) {
            is SessionEvent.MuxConnected -> listOf(
                NotificationAction.ShowForeground("Connected"),
            )

            is SessionEvent.MuxDisconnected -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                if (event.toolCallCount > 0) {
                    add(NotificationAction.PostSummary(event.toolCallCount))
                } else {
                    add(NotificationAction.PostWarning("Session ended with no tool calls"))
                }
            }

            is SessionEvent.StreamOpened -> listOf(
                NotificationAction.ShowForeground(
                    "Connected \u2022 ${event.streamCount} active stream${plural(event.streamCount)}",
                ),
            )

            is SessionEvent.StreamClosed -> listOf(
                NotificationAction.ShowForeground(
                    "Connected \u2022 ${event.streamCount} active stream${plural(event.streamCount)}",
                ),
            )

            is SessionEvent.ErrorOccurred -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostError(event.message, event.streamId))
            }

            is SessionEvent.ToolCallCompleted -> buildList {
                if (effectiveMode != NotificationMode.Every) return@buildList
                add(
                    NotificationAction.PostToolUsage(
                        event.event.toolName,
                        event.event.providerId,
                    ),
                )
            }

            is SessionEvent.CertRenewalStarted -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostInfo("Certificate renewal started"))
            }

            is SessionEvent.CertRenewalFailed -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostError("Certificate renewal failed: ${event.message}"))
            }

            is SessionEvent.CertExpired -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostError("Certificate expired"))
            }

            is SessionEvent.RateLimited -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostInfo("Rate limited. Retry after ${event.retryAfterMillis}ms"))
            }

            is SessionEvent.SecurityAlert -> buildList {
                if (effectiveMode == NotificationMode.Suppress) return@buildList
                add(NotificationAction.PostAlert(event.message))
            }

            is SessionEvent.PermissionChanged -> emptyList()
        }
    }

    private fun plural(count: Int): String = if (count == 1) "" else "s"
}
