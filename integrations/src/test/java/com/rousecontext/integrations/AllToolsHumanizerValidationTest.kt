package com.rousecontext.integrations

import android.content.Context
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.integrations.health.FakeHealthConnectRepository
import com.rousecontext.integrations.health.HealthConnectMcpServer
import com.rousecontext.integrations.notifications.NotificationMcpProvider
import com.rousecontext.integrations.outreach.OutreachMcpProvider
import com.rousecontext.integrations.usage.UsageMcpProvider
import com.rousecontext.mcp.core.McpServerProvider
import com.rousecontext.mcp.core.ToolNameHumanizer
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.Server
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * All-tools validation test for [ToolNameHumanizer].
 *
 * Dynamically enumerates every tool registered by every [McpServerProvider]
 * in this module, calls [ToolNameHumanizer.humanize] on each name, and
 * asserts none of them throw. This is the guard rail that keeps all live
 * tool names inside the snake_case contract: if a new tool lands with
 * CamelCase, punctuation, or any other format the humanizer rejects, this
 * test fails and the author must either fix the tool name or extend the
 * humanizer's grammar before the notification copy work in #347 can rely
 * on it.
 *
 * Tool names are discovered by wiring each provider up against a recording
 * [Server] mock and capturing `addTool(name, ...)` calls -- no hand
 * enumeration, so new tools are picked up automatically.
 */
@RunWith(RobolectricTestRunner::class)
class AllToolsHumanizerValidationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every registered tool name is accepted by the humanizer`() {
        val providers: List<McpServerProvider> = listOf(
            NotificationMcpProvider(
                dao = mockk(relaxed = true),
                activeNotificationSource = { emptyArray<StatusBarNotification>() },
                actionPerformer = { _, _ -> false },
                notificationDismisser = { _ -> false }
            ),
            // dndEnabled = true so the DND tools are registered and validated.
            OutreachMcpProvider(context = context, dndEnabled = true),
            UsageMcpProvider(context = context),
            HealthConnectMcpServer(repository = FakeHealthConnectRepository())
        )

        val toolNames = providers.flatMap { provider ->
            val recorder = recordingServer()
            provider.register(recorder.server)
            recorder.capturedToolNames
        }

        // Sanity: each provider should contribute at least one tool; an empty
        // registry would cause the loop below to vacuously pass.
        assertTrue(
            "No tool names captured -- provider wiring or recording is broken",
            toolNames.isNotEmpty()
        )

        val failures = toolNames.mapNotNull { name ->
            try {
                ToolNameHumanizer.humanize(name)
                null
            } catch (iae: IllegalArgumentException) {
                name to iae.message
            }
        }

        assertTrue(
            "Tool names rejected by ToolNameHumanizer: " +
                failures.joinToString { "'${it.first}' (${it.second})" },
            failures.isEmpty()
        )
    }

    // ---------- helpers ----------

    private class RecordingServer(val server: Server, val capturedToolNames: MutableList<String>)

    private fun recordingServer(): RecordingServer {
        val server = mockk<Server>(relaxed = true)
        val names = mutableListOf<String>()
        val nameSlot = slot<String>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any(),
                handler = any()
            )
        } answers {
            names.add(nameSlot.captured)
        }
        return RecordingServer(server, names)
    }
}
