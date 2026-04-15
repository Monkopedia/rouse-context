package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolResultConversionTest {

    @Test
    fun `success becomes non-error CallToolResult with text content`() {
        val r = ToolResult.Success("hello").toCallToolResult()
        assertFalse(r.isError == true)
        val text = (r.content.first() as TextContent).text
        assertEquals("hello", text)
    }

    @Test
    fun `error becomes isError=true with message preserved`() {
        val r = ToolResult.Error("bad things").toCallToolResult()
        assertTrue(r.isError == true)
        val text = (r.content.first() as TextContent).text
        assertEquals("bad things", text)
    }

    @Test
    fun `json serializes element into a text content block`() {
        val payload = buildJsonObject { put("ok", JsonPrimitive(true)) }
        val r = ToolResult.Json(payload).toCallToolResult()
        assertFalse(r.isError == true)
        val text = (r.content.first() as TextContent).text
        assertEquals("""{"ok":true}""", text)
    }
}
