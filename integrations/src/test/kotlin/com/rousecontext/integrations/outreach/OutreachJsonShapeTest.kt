package com.rousecontext.integrations.outreach

import com.rousecontext.mcp.tool.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #417 — manual JSON construction in
 * [OutreachMcpProvider] tool results was producing malformed payloads when
 * exception messages contained quotes/backslashes/control chars.
 *
 * These tests verify that the migrated [outreachError] / [outreachSuccess] /
 * [SendNotificationResult] / [DndState] / [SetDndStateResult] /
 * [NotificationChannelDto] / [InstalledApp] paths emit JSON that round-trips
 * cleanly even with adversarial payloads.
 *
 * No Android plumbing is required — these are pure JSON shape tests that
 * exercise the serialization layer.
 */
class OutreachJsonShapeTest {

    // ---- outreachError ----

    @Test
    fun `outreachError with plain message round-trips`() {
        val result = outreachError("Something broke")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals(false, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals("Something broke", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outreachError with double quotes round-trips`() {
        val result = outreachError("App \"X\" not found")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals("App \"X\" not found", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outreachError with backslash round-trips`() {
        val result = outreachError("Path C:\\Windows\\System32 is invalid")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals(
            "Path C:\\Windows\\System32 is invalid",
            parsed["error"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `outreachError with newline and tab round-trips`() {
        val result = outreachError("line1\nline2\twith tab")
        require(result is ToolResult.Error)
        val parsed = Json.parseToJsonElement(result.message).jsonObject
        assertEquals("line1\nline2\twith tab", parsed["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outreachError contains success false default`() {
        val result = outreachError("anything")
        require(result is ToolResult.Error)
        assertTrue(result.message.contains("\"success\":false"))
    }

    // ---- outreachSuccess ----

    @Test
    fun `outreachSuccess round-trips with quotes and backslash`() {
        val result = outreachSuccess("Launched: \"My App\" \\path")
        require(result is ToolResult.Success)
        val parsed = Json.parseToJsonElement(result.text).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals("Launched: \"My App\" \\path", parsed["message"]?.jsonPrimitive?.content)
    }

    @Test
    fun `outreachSuccess contains success true default`() {
        val result = outreachSuccess("ok")
        require(result is ToolResult.Success)
        assertTrue(result.text.contains("\"success\":true"))
    }

    // ---- SendNotificationResult ----

    @Test
    fun `SendNotificationResult round-trips`() {
        val json = OutreachJson.encodeToString(
            SendNotificationResult.serializer(),
            SendNotificationResult(notificationId = 12345)
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals(12345, parsed["notification_id"]?.jsonPrimitive?.int)
    }

    // ---- DndState / SetDndStateResult ----

    @Test
    fun `DndState round-trips without success field`() {
        val json = OutreachJson.encodeToString(
            DndState.serializer(),
            DndState(enabled = true, mode = "priority_only")
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        // get_dnd_state never wrote a success field; preserve that.
        assertTrue("get_dnd_state must not include success", !parsed.containsKey("success"))
        assertEquals(true, parsed["enabled"]?.jsonPrimitive?.boolean)
        assertEquals("priority_only", parsed["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `SetDndStateResult preserves nested previous_state`() {
        val json = OutreachJson.encodeToString(
            SetDndStateResult.serializer(),
            SetDndStateResult(
                previousState = DndState(enabled = false, mode = "off")
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        val prev = parsed["previous_state"]?.jsonObject
        assertEquals(false, prev?.get("enabled")?.jsonPrimitive?.boolean)
        assertEquals("off", prev?.get("mode")?.jsonPrimitive?.content)
    }

    // ---- NotificationChannelDto ----

    @Test
    fun `NotificationChannelDto with quoted name round-trips`() {
        val json = OutreachJson.encodeToString(
            NotificationChannelDto.serializer(),
            NotificationChannelDto(
                id = "rouse_ai_quote\"test",
                name = "Channel \"with quotes\"",
                description = "Desc with \\backslash and \"quote\"",
                importance = "high",
                vibration = true,
                sound = false,
                showBadge = true
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("rouse_ai_quote\"test", parsed["id"]?.jsonPrimitive?.content)
        assertEquals("Channel \"with quotes\"", parsed["name"]?.jsonPrimitive?.content)
        assertEquals(
            "Desc with \\backslash and \"quote\"",
            parsed["description"]?.jsonPrimitive?.content
        )
        assertEquals("high", parsed["importance"]?.jsonPrimitive?.content)
        assertEquals(true, parsed["vibration"]?.jsonPrimitive?.boolean)
        assertEquals(false, parsed["sound"]?.jsonPrimitive?.boolean)
        assertEquals(true, parsed["show_badge"]?.jsonPrimitive?.boolean)
    }

    // ---- InstalledApp ----

    @Test
    fun `InstalledApp with apostrophe and unicode in name round-trips`() {
        val json = OutreachJson.encodeToString(
            InstalledApp.serializer(),
            InstalledApp(
                `package` = "com.example.app",
                name = "Bob's “Smart” App ☕",
                system = false
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals("com.example.app", parsed["package"]?.jsonPrimitive?.content)
        assertEquals("Bob's “Smart” App ☕", parsed["name"]?.jsonPrimitive?.content)
        assertEquals(false, parsed["system"]?.jsonPrimitive?.boolean)
    }
}
