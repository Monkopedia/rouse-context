package com.rousecontext.integrations.usage

import com.rousecontext.mcp.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #417 — manual JSON construction in
 * [UsageMcpProvider] tool results was producing malformed payloads when
 * package names or app labels contained quotes/backslashes/control chars.
 *
 * These tests verify that the migrated DTOs ([UsageError], [AppUsageEntry],
 * [UsageSummary], [AppUsage], [UsageEvent], [UsageEventList], [UsageDelta],
 * [UsageComparison], [DailyUsage]) emit JSON that round-trips cleanly even
 * with adversarial payloads.
 */
class UsageJsonShapeTest {

    // ---- usageError ----

    @Test
    fun `usageError with quotes and backslash round-trips`() {
        val result = usageError("Bad value: \"foo\\bar\"")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals(false, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals("Bad value: \"foo\\bar\"", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `usageError with newline round-trips`() {
        val result = usageError("multi\nline\terror")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals("multi\nline\terror", parsed["error"]?.jsonPrimitive?.content)
    }

    // ---- AppUsageEntry ----

    @Test
    fun `AppUsageEntry with adversarial app name round-trips`() {
        val json = UsageJson.encodeToString(
            AppUsageEntry.serializer(),
            AppUsageEntry(
                `package` = "com.evil.app",
                name = "Evil \"App\" \\ name",
                foregroundMinutes = 42L
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("com.evil.app", parsed["package"]?.jsonPrimitive?.content)
        assertEquals("Evil \"App\" \\ name", parsed["name"]?.jsonPrimitive?.content)
        assertEquals(42L, parsed["foreground_minutes"]?.jsonPrimitive?.long)
    }

    // ---- UsageSummary ----

    @Test
    fun `UsageSummary with empty apps and odd period name round-trips`() {
        val json = UsageJson.encodeToString(
            UsageSummary.serializer(),
            UsageSummary(
                period = "today",
                totalMinutes = 0L,
                apps = emptyList()
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("today", parsed["period"]?.jsonPrimitive?.content)
        assertEquals(0L, parsed["total_minutes"]?.jsonPrimitive?.long)
        assertEquals(0, parsed["apps"]?.jsonArray?.size)
    }

    // ---- AppUsage ----

    @Test
    fun `AppUsage daily breakdown round-trips`() {
        val json = UsageJson.encodeToString(
            AppUsage.serializer(),
            AppUsage(
                `package` = "com.app",
                name = "Quoted \"App\"",
                period = "week",
                totalMinutes = 100L,
                daily = listOf(
                    DailyUsage(date = "2024-01-01", foregroundMinutes = 60L),
                    DailyUsage(date = "2024-01-02", foregroundMinutes = 40L)
                )
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("com.app", parsed["package"]?.jsonPrimitive?.content)
        assertEquals("Quoted \"App\"", parsed["name"]?.jsonPrimitive?.content)
        assertEquals("week", parsed["period"]?.jsonPrimitive?.content)
        assertEquals(100L, parsed["total_minutes"]?.jsonPrimitive?.long)
        val daily = parsed["daily"]?.jsonArray
        assertEquals(2, daily?.size)
        assertEquals("2024-01-01", daily?.get(0)?.jsonObject?.get("date")?.jsonPrimitive?.content)
    }

    // ---- UsageEvent / UsageEventList ----

    @Test
    fun `UsageEventList round-trips`() {
        val json = UsageJson.encodeToString(
            UsageEventList.serializer(),
            UsageEventList(
                events = listOf(
                    UsageEvent(
                        `package` = "com.x",
                        name = "X \"app\"",
                        type = "activity_resumed",
                        timestamp = "2024-01-01T00:00:00Z"
                    )
                )
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        val events = parsed["events"]?.jsonArray
        assertEquals(1, events?.size)
        val first = events?.get(0)?.jsonObject
        assertEquals("com.x", first?.get("package")?.jsonPrimitive?.content)
        assertEquals("X \"app\"", first?.get("name")?.jsonPrimitive?.content)
    }

    // ---- UsageComparison ----

    @Test
    fun `UsageComparison with negative change round-trips`() {
        val json = UsageJson.encodeToString(
            UsageComparison.serializer(),
            UsageComparison(
                period1 = "today",
                period2 = "yesterday",
                total1Minutes = 100L,
                total2Minutes = 80L,
                apps = listOf(
                    UsageDelta(
                        `package` = "com.x",
                        name = "X",
                        period1Minutes = 50L,
                        period2Minutes = 30L,
                        change = "-20 min"
                    )
                )
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("today", parsed["period1"]?.jsonPrimitive?.content)
        assertEquals(100L, parsed["total1_minutes"]?.jsonPrimitive?.long)
        val apps = parsed["apps"]?.jsonArray
        val first = apps?.get(0)?.jsonObject
        assertEquals("-20 min", first?.get("change")?.jsonPrimitive?.content)
    }

    @Test
    fun `usageError contains success false default`() {
        val result = usageError("oops")
        require(result is ToolResult.Error)
        assertTrue(result.message.contains("\"success\":false"))
    }
}
