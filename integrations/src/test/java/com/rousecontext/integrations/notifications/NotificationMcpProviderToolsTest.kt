package com.rousecontext.integrations.notifications

import android.service.notification.StatusBarNotification
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Lockdown test for the tool set registered by [NotificationMcpProvider].
 *
 * Pins down the exact set of tool names and verifies every tool's
 * [ToolSchema] round-trips through kotlinx-serialization without loss.
 * If any future change adds, removes, renames, or silently gates a tool,
 * this test fails with a clear diff of tool-name sets.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationMcpProviderToolsTest {

    private lateinit var server: Server
    private val toolNames = mutableListOf<String>()
    private val toolSchemas = mutableMapOf<String, ToolSchema>()

    @Before
    fun setUp() {
        server = mockk(relaxed = true)
        val nameSlot = slot<String>()
        val schemaSlot = slot<ToolSchema>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = capture(schemaSlot),
                handler = any()
            )
        } answers {
            toolNames.add(nameSlot.captured)
            toolSchemas[nameSlot.captured] = schemaSlot.captured
        }
    }

    private fun buildProvider(): NotificationMcpProvider = NotificationMcpProvider(
        dao = mockk(relaxed = true),
        activeNotificationSource = { emptyArray<StatusBarNotification>() },
        actionPerformer = { _, _ -> false },
        notificationDismisser = { _ -> false }
    )

    @Test
    fun `registers exactly the expected tool set`() {
        val expected = setOf(
            "list_active_notifications",
            "perform_notification_action",
            "dismiss_notification",
            "search_notification_history",
            "get_notification_stats"
        )

        buildProvider().register(server)

        assertEquals(expected, toolNames.toSet())
        // No duplicates — every name appears exactly once.
        assertEquals(expected.size, toolNames.size)
    }

    @Test
    fun `each tool schema round-trips cleanly through kotlinx-serialization`() {
        buildProvider().register(server)

        val json = Json { ignoreUnknownKeys = true }
        toolSchemas.forEach { (name, schema) ->
            val serialized = json.encodeToString(ToolSchema.serializer(), schema)
            val parsed = json.decodeFromString(ToolSchema.serializer(), serialized)
            assertEquals("$name schema round-trip", schema, parsed)
        }
    }
}
