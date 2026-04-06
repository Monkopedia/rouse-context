package com.rousecontext.outreach

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.mcp.core.Clock
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.modelcontextprotocol.kotlin.sdk.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
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

    // Captured tool handlers
    private val toolHandlers = mutableMapOf<String, suspend (CallToolRequest) -> CallToolResult>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        server = mockk(relaxed = true)

        // Capture tool registrations
        val nameSlot = slot<String>()
        val handlerSlot = slot<suspend (CallToolRequest) -> CallToolResult>()
        every {
            server.addTool(
                name = capture(nameSlot),
                description = any(),
                inputSchema = any<Tool.Input>(),
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
            "list_installed_apps",
            "get_dnd_state",
            "set_dnd_state"
        )
        assertEquals(expectedTools, toolHandlers.keys)
    }

    @Test
    fun `register without dnd skips DND tools`() {
        val noDndHandlers = mutableMapOf<String, suspend (CallToolRequest) -> CallToolResult>()
        val noDndServer = mockk<Server>(relaxed = true)
        val nameSlot2 = slot<String>()
        val handlerSlot2 = slot<suspend (CallToolRequest) -> CallToolResult>()
        every {
            noDndServer.addTool(
                name = capture(nameSlot2),
                description = any(),
                inputSchema = any<Tool.Input>(),
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
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("file:///etc/passwd"))
            }
        )
        val result = handler(request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Only http and https"))
    }

    @Test
    fun `open_link accepts https`() = runBlocking {
        val handler = toolHandlers["open_link"]!!
        val request = CallToolRequest(
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("https://example.com"))
            }
        )
        val result = handler(request)
        assertFalse(result.isError == true)
    }

    @Test
    fun `launch_app returns error for unknown package`() = runBlocking {
        val handler = toolHandlers["launch_app"]!!
        val request = CallToolRequest(
            name = "launch_app",
            arguments = buildJsonObject {
                put("package_name", JsonPrimitive("com.unknown.nonexistent.app"))
            }
        )
        val result = handler(request)
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("App not found"))
    }

    @Test
    fun `send_notification succeeds`() = runBlocking {
        val handler = toolHandlers["send_notification"]!!
        val request = CallToolRequest(
            name = "send_notification",
            arguments = buildJsonObject {
                put("title", JsonPrimitive("Test Title"))
                put("message", JsonPrimitive("Test Body"))
            }
        )
        val result = handler(request)
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
        val rateLimitHandlers = mutableMapOf<String, suspend (CallToolRequest) -> CallToolResult>()
        val rlServer = mockk<Server>(relaxed = true)
        val nameSlot3 = slot<String>()
        val handlerSlot3 = slot<suspend (CallToolRequest) -> CallToolResult>()
        every {
            rlServer.addTool(
                name = capture(nameSlot3),
                description = any(),
                inputSchema = any<Tool.Input>(),
                handler = capture(handlerSlot3)
            )
        } answers {
            rateLimitHandlers[nameSlot3.captured] = handlerSlot3.captured
        }

        val rlProvider = OutreachMcpProvider(context, dndEnabled = false, clock = clock)
        rlProvider.register(rlServer)

        val handler = rateLimitHandlers["send_notification"]!!
        val request = CallToolRequest(
            name = "send_notification",
            arguments = buildJsonObject {
                put("title", JsonPrimitive("Test"))
                put("message", JsonPrimitive("Body"))
            }
        )

        // Send 10 notifications (should all succeed)
        repeat(10) {
            val result = handler(request)
            assertFalse("Notification $it should succeed", result.isError == true)
        }

        // 11th should be rate limited
        val result = handler(request)
        assertTrue("11th notification should be rate limited", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Rate limited"))

        // Advance clock past the window
        clock.time += 61_000L
        val afterWindowResult = handler(request)
        assertFalse("Should succeed after window resets", afterWindowResult.isError == true)
    }

    @Test
    fun `get_dnd_state returns correct state`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        val handler = toolHandlers["get_dnd_state"]!!
        val request = CallToolRequest(
            name = "get_dnd_state",
            arguments = buildJsonObject {}
        )
        val result = handler(request)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"enabled\":true"))
        assertTrue(text.contains("\"mode\":\"priority_only\""))
    }

    @Test
    fun `set_dnd_state rejects when permission not granted`() = runBlocking {
        val handler = toolHandlers["set_dnd_state"]!!
        val request = CallToolRequest(
            name = "set_dnd_state",
            arguments = buildJsonObject {
                put("enabled", JsonPrimitive("true"))
            }
        )
        val result = handler(request)
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
            name = "set_dnd_state",
            arguments = buildJsonObject {
                put("enabled", JsonPrimitive("true"))
                put("mode", JsonPrimitive("total_silence"))
            }
        )
        val result = handler(request)
        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"success\":true"))

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            nm.currentInterruptionFilter
        )
    }
}
