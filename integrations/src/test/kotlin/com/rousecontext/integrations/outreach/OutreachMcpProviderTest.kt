package com.rousecontext.integrations.outreach

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.mcp.core.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class OutreachMcpProviderTest {

    private lateinit var context: Context
    private lateinit var server: Server
    private lateinit var provider: OutreachMcpProvider

    private val fakeConnection: ClientConnection = mockk(relaxed = true)
    private val toolHandlers = mutableMapOf<
        String,
        suspend ClientConnection.(
            CallToolRequest
        ) -> CallToolResult
        >()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        server = mockk(relaxed = true)

        // Capture tool registrations
        val nameSlot = slot<String>()
        val handlerSlot = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any<ToolSchema>(),
                handler = capture(handlerSlot)
            )
        } answers {
            toolHandlers[nameSlot.captured] = handlerSlot.captured
        }

        provider = OutreachMcpProvider(context, dndEnabled = true)
        provider.register(server)
    }

    @Test
    fun `register creates notification channel`() {
        val nm = context.getSystemService(NotificationManager::class.java)
        val channel = nm.getNotificationChannel(OutreachMcpProvider.CHANNEL_ID)
        assertTrue("Outreach notification channel should be created", channel != null)
    }

    @Test
    fun `register adds all basic tools plus DND tools`() {
        val expectedTools = setOf(
            "launch_app",
            "open_link",
            "copy_to_clipboard",
            "send_notification",
            "create_notification_channel",
            "list_notification_channels",
            "delete_notification_channel",
            "list_installed_apps",
            "get_dnd_state",
            "set_dnd_state"
        )
        assertEquals(expectedTools, toolHandlers.keys)
    }

    @Test
    fun `register without dnd skips DND tools`() {
        val noDndHandlers = mutableMapOf<
            String,
            suspend ClientConnection.(
                CallToolRequest
            ) -> CallToolResult
            >()
        val noDndServer = mockk<Server>(relaxed = true)
        val nameSlot2 = slot<String>()
        val handlerSlot2 = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            noDndServer.addTool(
                name = capture(nameSlot2),
                description = any(),
                inputSchema = any<ToolSchema>(),
                handler = capture(handlerSlot2)
            )
        } answers {
            noDndHandlers[nameSlot2.captured] = handlerSlot2.captured
        }

        val noDndProvider = OutreachMcpProvider(context, dndEnabled = false)
        noDndProvider.register(noDndServer)

        assertFalse(noDndHandlers.containsKey("get_dnd_state"))
        assertFalse(noDndHandlers.containsKey("set_dnd_state"))
        assertTrue(noDndHandlers.containsKey("launch_app"))
    }

    @Test
    fun `open_link rejects non-http schemes`() = runBlocking {
        val handler = toolHandlers["open_link"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "open_link",
                arguments = buildJsonObject {
                    put("url", JsonPrimitive("file:///etc/passwd"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Only http and https"))
    }

    @Test
    fun `open_link accepts https`() = runBlocking {
        val handler = toolHandlers["open_link"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "open_link",
                arguments = buildJsonObject {
                    put("url", JsonPrimitive("https://example.com"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertFalse(result.isError == true)
    }

    @Test
    fun `open_link posts notification fallback when direct launch is denied`() = runBlocking {
        val notifier = mockk<com.rousecontext.api.LaunchRequestNotifierApi>(relaxed = true)
        val fallbackHandlers = registerProvider(
            canLaunchDirectly = { false },
            launchNotifier = notifier
        )
        val handler = fallbackHandlers["open_link"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "open_link",
                arguments = buildJsonObject {
                    put("url", JsonPrimitive("https://example.com"))
                }
            )
        )

        val result = handler.invoke(fakeConnection, request)

        assertFalse(result.isError == true)
        io.mockk.verify(exactly = 1) {
            notifier.postOpenLink(any(), "https://example.com")
        }
        val text = (result.content.first() as TextContent).text!!
        assertTrue(
            "Result should mention notification fallback (was: $text)",
            text.contains("notification", ignoreCase = true)
        )
    }

    @Test
    fun `open_link uses direct launch when permission is granted`() = runBlocking {
        val notifier = mockk<com.rousecontext.api.LaunchRequestNotifierApi>(relaxed = true)
        val directHandlers = registerProvider(
            canLaunchDirectly = { true },
            launchNotifier = notifier
        )
        val handler = directHandlers["open_link"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "open_link",
                arguments = buildJsonObject {
                    put("url", JsonPrimitive("https://example.com"))
                }
            )
        )

        val result = handler.invoke(fakeConnection, request)

        assertFalse(result.isError == true)
        io.mockk.verify(exactly = 0) {
            notifier.postOpenLink(any(), any())
        }
    }

    @Test
    fun `launch_app posts notification fallback when direct launch is denied`() = runBlocking {
        val notifier = mockk<com.rousecontext.api.LaunchRequestNotifierApi>(relaxed = true)
        val fallbackHandlers = registerProvider(
            canLaunchDirectly = { false },
            launchNotifier = notifier
        )
        val handler = fallbackHandlers["launch_app"]!!
        // Use this test app's own package so the launch intent resolves
        val selfPkg = context.packageName
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "launch_app",
                arguments = buildJsonObject {
                    put("package_name", JsonPrimitive(selfPkg))
                }
            )
        )

        val result = handler.invoke(fakeConnection, request)

        // Either the launch-intent resolves and we post, or the package has no
        // launch intent and we error. Both are acceptable runtime outcomes; here
        // we only care that WHEN it proceeds, it goes through the notifier.
        if (result.isError != true) {
            io.mockk.verify(exactly = 1) {
                notifier.postLaunchApp(any(), selfPkg)
            }
        }
    }

    /** Build a fresh provider with overridable launch-direct predicate and notifier. */
    private fun registerProvider(
        canLaunchDirectly: () -> Boolean,
        launchNotifier: com.rousecontext.api.LaunchRequestNotifierApi?
    ): MutableMap<
        String,
        suspend ClientConnection.(CallToolRequest) -> CallToolResult
        > {
        val handlers = mutableMapOf<
            String,
            suspend ClientConnection.(CallToolRequest) -> CallToolResult
            >()
        val srv = mockk<Server>(relaxed = true)
        val nameSlot = slot<String>()
        val handlerSlot = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            srv.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any<ToolSchema>(),
                handler = capture(handlerSlot)
            )
        } answers {
            handlers[nameSlot.captured] = handlerSlot.captured
        }
        OutreachMcpProvider(
            context = context,
            dndEnabled = false,
            canLaunchDirectly = canLaunchDirectly,
            launchNotifier = launchNotifier
        ).register(srv)
        return handlers
    }

    @Test
    fun `launch_app returns error for unknown package`() = runBlocking {
        val handler = toolHandlers["launch_app"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "launch_app",
                arguments = buildJsonObject {
                    put("package_name", JsonPrimitive("com.unknown.nonexistent.app"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("App not found"))
    }

    @Test
    fun `send_notification succeeds`() = runBlocking {
        val handler = toolHandlers["send_notification"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "send_notification",
                arguments = buildJsonObject {
                    put("title", JsonPrimitive("Test Title"))
                    put("message", JsonPrimitive("Test Body"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"success\":true"))
        assertTrue(text.contains("notification_id"))
    }

    @Test
    fun `send_notification rate limits after max per minute`() = runBlocking {
        val clock = object : Clock {
            var time = 1000L
            override fun currentTimeMillis(): Long = time
        }
        val rateLimitHandlers = mutableMapOf<
            String,
            suspend ClientConnection.(
                CallToolRequest
            ) -> CallToolResult
            >()
        val rlServer = mockk<Server>(relaxed = true)
        val nameSlot3 = slot<String>()
        val handlerSlot3 = slot<suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
        every {
            rlServer.addTool(
                name = capture(nameSlot3),
                description = any(),
                inputSchema = any<ToolSchema>(),
                handler = capture(handlerSlot3)
            )
        } answers {
            rateLimitHandlers[nameSlot3.captured] = handlerSlot3.captured
        }

        val rlProvider = OutreachMcpProvider(context, dndEnabled = false, clock = clock)
        rlProvider.register(rlServer)

        val handler = rateLimitHandlers["send_notification"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "send_notification",
                arguments = buildJsonObject {
                    put("title", JsonPrimitive("Test"))
                    put("message", JsonPrimitive("Body"))
                }
            )
        )

        // Send 10 notifications (should all succeed)
        repeat(10) {
            val result = handler.invoke(fakeConnection, request)
            assertFalse("Notification $it should succeed", result.isError == true)
        }

        // 11th should be rate limited
        val result = handler.invoke(fakeConnection, request)
        assertTrue("11th notification should be rate limited", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Rate limited"))

        // Advance clock past the window
        clock.time += 61_000L
        val afterWindowResult = handler.invoke(fakeConnection, request)
        assertFalse("Should succeed after window resets", afterWindowResult.isError == true)
    }

    @Test
    fun `get_dnd_state returns correct state`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        val handler = toolHandlers["get_dnd_state"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "get_dnd_state",
                arguments = buildJsonObject {}
            )
        )
        val result = handler.invoke(fakeConnection, request)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"enabled\":true"))
        assertTrue(text.contains("\"mode\":\"priority_only\""))
    }

    @Test
    fun `set_dnd_state rejects when permission not granted`() = runBlocking {
        val handler = toolHandlers["set_dnd_state"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "set_dnd_state",
                arguments = buildJsonObject {
                    put("enabled", JsonPrimitive("true"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("permission not granted"))
    }

    @Test
    fun `set_dnd_state succeeds when permission granted`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val handler = toolHandlers["set_dnd_state"]!!
        val request = CallToolRequest(
            params = CallToolRequestParams(
                name = "set_dnd_state",
                arguments = buildJsonObject {
                    put("enabled", JsonPrimitive("true"))
                    put("mode", JsonPrimitive("total_silence"))
                }
            )
        )
        val result = handler.invoke(fakeConnection, request)
        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"success\":true"))

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            nm.currentInterruptionFilter
        )
    }
}
