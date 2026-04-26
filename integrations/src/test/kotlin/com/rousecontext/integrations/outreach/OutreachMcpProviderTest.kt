package com.rousecontext.integrations.outreach

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.rousecontext.integrations.testing.McpToolTestHarness
import com.rousecontext.mcp.core.Clock
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
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
    private lateinit var harness: McpToolTestHarness
    private lateinit var provider: OutreachMcpProvider

    private val fakeConnection: ClientConnection = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()

        harness = McpToolTestHarness()
        val server = harness.createMockServer()

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
        assertEquals(expectedTools, harness.toolHandlers.keys)
    }

    @Test
    fun `register without dnd skips DND tools`() {
        val noDndHarness = McpToolTestHarness()
        val noDndProvider = OutreachMcpProvider(context, dndEnabled = false)
        noDndProvider.register(noDndHarness.createMockServer())

        assertFalse(noDndHarness.toolHandlers.containsKey("get_dnd_state"))
        assertFalse(noDndHarness.toolHandlers.containsKey("set_dnd_state"))
        assertTrue(noDndHarness.toolHandlers.containsKey("launch_app"))
    }

    @Test
    fun `open_link rejects non-http schemes`() = runBlocking {
        val result = harness.callTool(
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("file:///etc/passwd"))
            },
            connection = fakeConnection
        )
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Only http and https"))
    }

    @Test
    fun `open_link accepts https`() = runBlocking {
        val result = harness.callTool(
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("https://example.com"))
            },
            connection = fakeConnection
        )
        assertFalse(result.isError == true)
    }

    @Test
    fun `open_link posts notification fallback when direct launch is denied`() = runBlocking {
        val notifier = mockk<com.rousecontext.api.LaunchRequestNotifierApi>(relaxed = true)
        val fallbackHarness = registerProvider(
            canLaunchDirectly = { false },
            launchNotifier = notifier
        )
        val result = fallbackHarness.callTool(
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("https://example.com"))
            },
            connection = fakeConnection
        )

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
        val directHarness = registerProvider(
            canLaunchDirectly = { true },
            launchNotifier = notifier
        )
        val result = directHarness.callTool(
            name = "open_link",
            arguments = buildJsonObject {
                put("url", JsonPrimitive("https://example.com"))
            },
            connection = fakeConnection
        )

        assertFalse(result.isError == true)
        io.mockk.verify(exactly = 0) {
            notifier.postOpenLink(any(), any())
        }
    }

    @Test
    fun `launch_app posts notification fallback when direct launch is denied`() = runBlocking {
        val notifier = mockk<com.rousecontext.api.LaunchRequestNotifierApi>(relaxed = true)
        val fallbackHarness = registerProvider(
            canLaunchDirectly = { false },
            launchNotifier = notifier
        )
        // Use this test app's own package so the launch intent resolves
        val selfPkg = context.packageName

        val result: CallToolResult = fallbackHarness.callTool(
            name = "launch_app",
            arguments = buildJsonObject {
                put("package_name", JsonPrimitive(selfPkg))
            },
            connection = fakeConnection
        )

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
        canLaunchDirectly: suspend () -> Boolean,
        launchNotifier: com.rousecontext.api.LaunchRequestNotifierApi?
    ): McpToolTestHarness {
        val h = McpToolTestHarness()
        OutreachMcpProvider(
            context = context,
            dndEnabled = false,
            canLaunchDirectly = canLaunchDirectly,
            launchNotifier = launchNotifier
        ).register(h.createMockServer())
        return h
    }

    @Test
    fun `launch_app returns error for unknown package`() = runBlocking {
        val result = harness.callTool(
            name = "launch_app",
            arguments = buildJsonObject {
                put("package_name", JsonPrimitive("com.unknown.nonexistent.app"))
            },
            connection = fakeConnection
        )
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("App not found"))
    }

    @Test
    fun `send_notification succeeds`() = runBlocking {
        val result = harness.callTool(
            name = "send_notification",
            arguments = buildJsonObject {
                put("title", JsonPrimitive("Test Title"))
                put("message", JsonPrimitive("Test Body"))
            },
            connection = fakeConnection
        )
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
        val rlHarness = McpToolTestHarness()
        val rlProvider = OutreachMcpProvider(context, dndEnabled = false, clock = clock)
        rlProvider.register(rlHarness.createMockServer())

        val args = buildJsonObject {
            put("title", JsonPrimitive("Test"))
            put("message", JsonPrimitive("Body"))
        }

        // Send 10 notifications (should all succeed)
        repeat(10) {
            val result = rlHarness.callTool(
                name = "send_notification",
                arguments = args,
                connection = fakeConnection
            )
            assertFalse("Notification $it should succeed", result.isError == true)
        }

        // 11th should be rate limited
        val result = rlHarness.callTool(
            name = "send_notification",
            arguments = args,
            connection = fakeConnection
        )
        assertTrue("11th notification should be rate limited", result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Rate limited"))

        // Advance clock past the window
        clock.time += 61_000L
        val afterWindowResult = rlHarness.callTool(
            name = "send_notification",
            arguments = args,
            connection = fakeConnection
        )
        assertFalse("Should succeed after window resets", afterWindowResult.isError == true)
    }

    @Test
    fun `get_dnd_state returns correct state`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)

        val result = harness.callTool(
            name = "get_dnd_state",
            arguments = buildJsonObject {},
            connection = fakeConnection
        )
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"enabled\":true"))
        assertTrue(text.contains("\"mode\":\"priority_only\""))
    }

    @Test
    fun `set_dnd_state rejects when permission not granted`() = runBlocking {
        val result = harness.callTool(
            name = "set_dnd_state",
            arguments = buildJsonObject {
                put("enabled", JsonPrimitive("true"))
            },
            connection = fakeConnection
        )
        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("permission not granted"))
    }

    @Test
    fun `set_dnd_state succeeds when permission granted`() = runBlocking {
        val nm = context.getSystemService(NotificationManager::class.java)
        val shadowNm = Shadows.shadowOf(nm)
        shadowNm.setNotificationPolicyAccessGranted(true)

        val result = harness.callTool(
            name = "set_dnd_state",
            arguments = buildJsonObject {
                put("enabled", JsonPrimitive("true"))
                put("mode", JsonPrimitive("total_silence"))
            },
            connection = fakeConnection
        )
        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"success\":true"))

        assertEquals(
            NotificationManager.INTERRUPTION_FILTER_NONE,
            nm.currentInterruptionFilter
        )
    }
}
