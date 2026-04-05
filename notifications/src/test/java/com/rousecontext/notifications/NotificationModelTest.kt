package com.rousecontext.notifications

import com.rousecontext.mcp.core.ToolCallEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationModelTest {

    private val summarySettings = NotificationSettings(
        mode = NotificationMode.Summary,
        permissionGranted = true,
    )

    private val everySettings = NotificationSettings(
        mode = NotificationMode.Every,
        permissionGranted = true,
    )

    private val suppressSettings = NotificationSettings(
        mode = NotificationMode.Suppress,
        permissionGranted = true,
    )

    // --- MuxConnected ---

    @Test
    fun `MuxConnected emits ShowForeground`() {
        val actions = NotificationModel.onEvent(SessionEvent.MuxConnected, summarySettings)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is NotificationAction.ShowForeground)
    }

    // --- MuxDisconnected ---

    @Test
    fun `MuxDisconnected with tool calls in Summary mode emits PostSummary`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.MuxDisconnected(toolCallCount = 5),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val summary = actions[0] as NotificationAction.PostSummary
        assertEquals(5, summary.toolCallCount)
    }

    @Test
    fun `MuxDisconnected with zero tool calls emits PostWarning`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.MuxDisconnected(toolCallCount = 0),
            summarySettings,
        )
        assertEquals(1, actions.size)
        assertTrue(actions[0] is NotificationAction.PostWarning)
    }

    // --- StreamOpened / StreamClosed ---

    @Test
    fun `StreamOpened updates foreground with stream count`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.StreamOpened(streamCount = 3),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val fg = actions[0] as NotificationAction.ShowForeground
        assertTrue(fg.message.contains("3"))
    }

    @Test
    fun `StreamClosed updates foreground with stream count`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.StreamClosed(streamCount = 1),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val fg = actions[0] as NotificationAction.ShowForeground
        assertTrue(fg.message.contains("1"))
        // Singular form for count of 1
        assertTrue(fg.message.contains("stream") && !fg.message.contains("streams"))
    }

    // --- ErrorOccurred ---

    @Test
    fun `ErrorOccurred connection-level emits PostError`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.ErrorOccurred("Relay unreachable"),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val error = actions[0] as NotificationAction.PostError
        assertEquals("Relay unreachable", error.message)
        assertEquals(null, error.streamId)
    }

    @Test
    fun `ErrorOccurred stream-level emits PostError with stream context`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.ErrorOccurred("TLS failed", streamId = 7),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val error = actions[0] as NotificationAction.PostError
        assertEquals(7, error.streamId)
    }

    // --- ToolCallCompleted ---

    @Test
    fun `ToolCallCompleted in Every mode emits PostToolUsage`() {
        val event = ToolCallEvent(
            sessionId = "s1",
            toolName = "get_steps",
            provider = "health",
            timestampMillis = 1000L,
            durationMillis = 50L,
            success = true,
        )
        val actions = NotificationModel.onEvent(
            SessionEvent.ToolCallCompleted(event),
            everySettings,
        )
        assertEquals(1, actions.size)
        val usage = actions[0] as NotificationAction.PostToolUsage
        assertEquals("get_steps", usage.toolName)
        assertEquals("health", usage.provider)
    }

    @Test
    fun `ToolCallCompleted in Summary mode emits nothing`() {
        val event = ToolCallEvent(
            sessionId = "s1",
            toolName = "get_steps",
            provider = "health",
            timestampMillis = 1000L,
            durationMillis = 50L,
            success = true,
        )
        val actions = NotificationModel.onEvent(
            SessionEvent.ToolCallCompleted(event),
            summarySettings,
        )
        assertTrue(actions.isEmpty())
    }

    // --- Suppress mode ---

    @Test
    fun `Suppress mode emits no optional notifications`() {
        val events = listOf(
            SessionEvent.MuxDisconnected(toolCallCount = 3),
            SessionEvent.ErrorOccurred("fail"),
            SessionEvent.CertRenewalStarted,
            SessionEvent.CertRenewalFailed("timeout"),
            SessionEvent.CertExpired,
            SessionEvent.RateLimited(60_000L),
            SessionEvent.SecurityAlert("suspicious"),
        )

        events.forEach { event ->
            val actions = NotificationModel.onEvent(event, suppressSettings)
            assertTrue(
                "Expected no actions for $event in Suppress mode, got $actions",
                actions.isEmpty(),
            )
        }
    }

    @Test
    fun `Suppress mode still shows foreground notifications`() {
        val connectActions = NotificationModel.onEvent(SessionEvent.MuxConnected, suppressSettings)
        assertEquals(1, connectActions.size)
        assertTrue(connectActions[0] is NotificationAction.ShowForeground)

        val streamActions = NotificationModel.onEvent(
            SessionEvent.StreamOpened(streamCount = 2),
            suppressSettings,
        )
        assertEquals(1, streamActions.size)
        assertTrue(streamActions[0] is NotificationAction.ShowForeground)
    }

    // --- Permission denied forces suppress ---

    @Test
    fun `Permission denied forces suppress for optional notifications`() {
        val deniedSettings = NotificationSettings(
            mode = NotificationMode.Every,
            permissionGranted = false,
        )

        // Optional notification suppressed
        val event = ToolCallEvent(
            sessionId = "s1",
            toolName = "get_steps",
            provider = "health",
            timestampMillis = 1000L,
            durationMillis = 50L,
            success = true,
        )
        val toolActions = NotificationModel.onEvent(
            SessionEvent.ToolCallCompleted(event),
            deniedSettings,
        )
        assertTrue(toolActions.isEmpty())

        // Foreground still works
        val fgActions = NotificationModel.onEvent(SessionEvent.MuxConnected, deniedSettings)
        assertEquals(1, fgActions.size)
        assertTrue(fgActions[0] is NotificationAction.ShowForeground)
    }

    // --- Certificate events ---

    @Test
    fun `CertRenewalStarted emits PostInfo`() {
        val actions = NotificationModel.onEvent(SessionEvent.CertRenewalStarted, summarySettings)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is NotificationAction.PostInfo)
    }

    @Test
    fun `CertRenewalFailed emits PostError`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.CertRenewalFailed("ACME timeout"),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val error = actions[0] as NotificationAction.PostError
        assertTrue(error.message.contains("ACME timeout"))
    }

    @Test
    fun `CertExpired emits PostError`() {
        val actions = NotificationModel.onEvent(SessionEvent.CertExpired, summarySettings)
        assertEquals(1, actions.size)
        assertTrue(actions[0] is NotificationAction.PostError)
    }

    // --- RateLimited ---

    @Test
    fun `RateLimited emits PostInfo with retry info`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.RateLimited(retryAfterMillis = 30_000L),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val info = actions[0] as NotificationAction.PostInfo
        assertTrue(info.message.contains("30000"))
    }

    // --- SecurityAlert ---

    @Test
    fun `SecurityAlert emits PostAlert`() {
        val actions = NotificationModel.onEvent(
            SessionEvent.SecurityAlert("Unauthorized access attempt"),
            summarySettings,
        )
        assertEquals(1, actions.size)
        val alert = actions[0] as NotificationAction.PostAlert
        assertEquals("Unauthorized access attempt", alert.message)
    }
}
