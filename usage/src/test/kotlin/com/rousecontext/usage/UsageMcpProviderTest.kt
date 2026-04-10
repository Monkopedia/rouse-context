package com.rousecontext.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
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

class UsageMcpProviderTest {

    private lateinit var context: Context
    private lateinit var usageStatsManager: UsageStatsManager
    private lateinit var packageManager: PackageManager
    private lateinit var server: Server
    private lateinit var provider: UsageMcpProvider

    private val toolHandlers =
        mutableMapOf<String, suspend ClientConnection.(CallToolRequest) -> CallToolResult>()
    private val fakeConnection: ClientConnection = mockk(relaxed = true)

    @Before
    fun setUp() {
        usageStatsManager = mockk(relaxed = true)
        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true)

        every { context.getSystemService(UsageStatsManager::class.java) } returns usageStatsManager
        every { context.packageManager } returns packageManager

        // Default: resolve package names to readable labels
        val appInfo = mockk<ApplicationInfo>()
        every { packageManager.getApplicationLabel(any()) } returns "Unknown"
        every {
            packageManager.getApplicationInfo(any<String>(), any<Int>())
        } returns appInfo

        server = mockk(relaxed = true)

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

        provider = UsageMcpProvider(context)
        provider.register(server)
    }

    @Test
    fun `register adds all four tools`() {
        val expectedTools = setOf(
            "get_usage_summary",
            "get_app_usage",
            "get_usage_events",
            "compare_usage"
        )
        assertEquals(expectedTools, toolHandlers.keys)
    }

    @Test
    fun `get_usage_summary returns sorted breakdown`() = runBlocking {
        val stats1 = mockUsageStats("com.example.app1", totalForeground = 3_600_000L)
        val stats2 = mockUsageStats("com.example.app2", totalForeground = 7_200_000L)
        val stats3 = mockUsageStats("com.example.app3", totalForeground = 0L)

        every {
            usageStatsManager.queryUsageStats(any(), any(), any())
        } returns listOf(stats1, stats2, stats3)

        every { packageManager.getApplicationLabel(any()) } returns "App"
        setupAppLabel("com.example.app1", "App One")
        setupAppLabel("com.example.app2", "App Two")
        setupAppLabel("com.example.app3", "App Three")

        val handler = toolHandlers["get_usage_summary"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_summary",
                    arguments = buildJsonObject {
                        put("period", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        // App Two (7200s) should appear before App One (3600s)
        val app2Idx = text.indexOf("App Two")
        val app1Idx = text.indexOf("App One")
        assertTrue("App Two should appear before App One", app2Idx < app1Idx)
        // Zero-usage apps should be excluded
        assertFalse("Zero-usage apps excluded", text.contains("App Three"))
    }

    @Test
    fun `get_usage_summary deduplicates entries per package`() = runBlocking {
        // UsageStatsManager may return multiple entries per package for different buckets
        val msPerMin = 60_000L
        val dup1 = mockUsageStats("com.example.app1", totalForeground = 1_080 * msPerMin)
        val dup2 = mockUsageStats("com.example.app1", totalForeground = 42 * msPerMin)
        val other = mockUsageStats("com.example.app2", totalForeground = 500 * msPerMin)

        every {
            usageStatsManager.queryUsageStats(any(), any(), any())
        } returns listOf(dup1, dup2, other)

        setupAppLabel("com.example.app1", "Rouse Context")
        setupAppLabel("com.example.app2", "Other App")

        val handler = toolHandlers["get_usage_summary"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_summary",
                    arguments = buildJsonObject {
                        put("period", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        // Should appear exactly once with summed time (1080 + 42 = 1122 min)
        assertEquals(
            "Package should appear exactly once",
            1,
            Regex("com\\.example\\.app1").findAll(text).count()
        )
        assertTrue("Should contain merged total", text.contains("1122"))
        // Total should be 1122 + 500 = 1622
        assertTrue("Total should be summed correctly", text.contains("1622"))
    }

    @Test
    fun `get_usage_summary respects limit`() = runBlocking {
        val stats = (1..5).map { i ->
            mockUsageStats("com.example.app$i", totalForeground = (i * 1_000_000L))
        }
        every {
            usageStatsManager.queryUsageStats(any(), any(), any())
        } returns stats

        (1..5).forEach { i ->
            setupAppLabel("com.example.app$i", "App$i")
        }

        val handler = toolHandlers["get_usage_summary"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_summary",
                    arguments = buildJsonObject {
                        put("period", JsonPrimitive("week"))
                        put("limit", JsonPrimitive(2))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        // Should contain top 2 apps (App5, App4) but not App1
        assertTrue(text.contains("App5"))
        assertTrue(text.contains("App4"))
        assertFalse(text.contains("App1"))
    }

    @Test
    fun `get_usage_summary rejects invalid period`() = runBlocking {
        val handler = toolHandlers["get_usage_summary"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_summary",
                    arguments = buildJsonObject {
                        put("period", JsonPrimitive("invalid"))
                    }
                )
            )
        )

        assertTrue(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Invalid period"))
    }

    @Test
    fun `get_app_usage returns daily breakdown`() = runBlocking {
        val stats = mockUsageStats("com.example.target", totalForeground = 5_400_000L)
        every {
            usageStatsManager.queryUsageStats(any(), any(), any())
        } returns listOf(stats)

        setupAppLabel("com.example.target", "Target App")

        val handler = toolHandlers["get_app_usage"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_app_usage",
                    arguments = buildJsonObject {
                        put("package_name", JsonPrimitive("com.example.target"))
                        put("period", JsonPrimitive("week"))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("Target App"))
        assertTrue(text.contains("com.example.target"))
    }

    @Test
    fun `get_app_usage returns error for missing package_name`() = runBlocking {
        val handler = toolHandlers["get_app_usage"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_app_usage",
                    arguments = buildJsonObject {
                        put("period", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertTrue(result.isError == true)
    }

    @Test
    fun `get_usage_events returns empty list when no events`() = runBlocking {
        val events = mockk<UsageEvents>()
        every { events.hasNextEvent() } returns false

        every {
            usageStatsManager.queryEvents(any(), any())
        } returns events

        val handler = toolHandlers["get_usage_events"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_events",
                    arguments = buildJsonObject {
                        put("since", JsonPrimitive("today"))
                        put("until", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("\"events\":[]"))
    }

    @Test
    fun `get_usage_events rejects invalid since period`() = runBlocking {
        val handler = toolHandlers["get_usage_events"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "get_usage_events",
                    arguments = buildJsonObject {
                        put("since", JsonPrimitive("bad"))
                        put("until", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertTrue(result.isError == true)
    }

    @Test
    fun `compare_usage shows changes between periods`() = runBlocking {
        val statsP1 = listOf(
            mockUsageStats("com.example.app1", totalForeground = 3_600_000L)
        )
        val statsP2 = listOf(
            mockUsageStats("com.example.app1", totalForeground = 7_200_000L)
        )

        var queryCount = 0
        every {
            usageStatsManager.queryUsageStats(any(), any(), any())
        } answers {
            if (queryCount++ == 0) statsP1 else statsP2
        }

        setupAppLabel("com.example.app1", "App One")

        val handler = toolHandlers["compare_usage"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "compare_usage",
                    arguments = buildJsonObject {
                        put("period1", JsonPrimitive("yesterday"))
                        put("period2", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertFalse(result.isError == true)
        val text = (result.content.first() as TextContent).text!!
        assertTrue(text.contains("App One"))
    }

    @Test
    fun `compare_usage rejects invalid period1`() = runBlocking {
        val handler = toolHandlers["compare_usage"]!!
        val result = handler.invoke(
            fakeConnection,
            CallToolRequest(
                params = CallToolRequestParams(
                    name = "compare_usage",
                    arguments = buildJsonObject {
                        put("period1", JsonPrimitive("bad"))
                        put("period2", JsonPrimitive("today"))
                    }
                )
            )
        )

        assertTrue(result.isError == true)
    }

    private fun mockUsageStats(packageName: String, totalForeground: Long): UsageStats {
        val stats = mockk<UsageStats>()
        every { stats.packageName } returns packageName
        every { stats.totalTimeInForeground } returns totalForeground
        every { stats.firstTimeStamp } returns 0L
        every { stats.lastTimeStamp } returns System.currentTimeMillis()
        every { stats.lastTimeUsed } returns System.currentTimeMillis()
        return stats
    }

    @Suppress("DEPRECATION")
    private fun setupAppLabel(packageName: String, label: String) {
        val info = mockk<ApplicationInfo>()
        every { packageManager.getApplicationInfo(packageName, any<Int>()) } returns info
        every { packageManager.getApplicationLabel(info) } returns label
    }
}
