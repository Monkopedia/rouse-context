package com.rousecontext.integrations.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for issue #417 — manual JSON construction in
 * [NotificationMcpProvider] tool results was producing malformed payloads
 * when notification keys or messages contained quotes/backslashes.
 *
 * These tests verify that the migrated [NotificationStatusMessage] and
 * [NotificationSuccess] paths emit JSON that round-trips cleanly even with
 * adversarial payloads.
 */
class NotificationJsonShapeTest {

    @Test
    fun `NotificationStatusMessage with quotes and backslash round-trips`() {
        val json = NotificationJson.encodeToString(
            NotificationStatusMessage.serializer(),
            NotificationStatusMessage(
                success = false,
                message = "Cannot act on \"Rouse\" \\ notifications"
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(false, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            "Cannot act on \"Rouse\" \\ notifications",
            parsed["message"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `NotificationStatusMessage with newline and tab round-trips`() {
        val json = NotificationJson.encodeToString(
            NotificationStatusMessage.serializer(),
            NotificationStatusMessage(
                success = true,
                message = "Action\nperformed\twith newlines"
            )
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(
            "Action\nperformed\twith newlines",
            parsed["message"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `NotificationSuccess with true round-trips`() {
        val json = NotificationJson.encodeToString(
            NotificationSuccess.serializer(),
            NotificationSuccess(success = true)
        )
        val parsed = Json.parseToJsonElement(json).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.boolean)
        // Must NOT contain a `message` field (legacy shape was just `{"success":bool}`).
        assertFalse(
            "NotificationSuccess must not include a message field",
            parsed.containsKey("message")
        )
    }

    @Test
    fun `errActionsDisabled emits stable shape`() {
        val text = NotificationMcpProvider.errActionsDisabled()
        val parsed = Json.parseToJsonElement(text).jsonObject
        assertEquals(false, parsed["success"]?.jsonPrimitive?.boolean)
        assertEquals(
            "Notification actions are disabled by the user.",
            parsed["message"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `errActionsDisabled is parseable JSON not literal template`() {
        val text = NotificationMcpProvider.errActionsDisabled()
        // Verify it parses cleanly (the original `const` literal was already
        // safe because it had no interpolated content; this ensures the
        // refactor doesn't regress that property).
        Json.parseToJsonElement(text)
        assertTrue(text.contains("\"success\":false"))
    }
}
