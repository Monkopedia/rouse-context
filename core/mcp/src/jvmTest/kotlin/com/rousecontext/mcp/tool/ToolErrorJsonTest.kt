package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that both framework-level error paths in [McpTool] -- missing required
 * param and unhandled exception -- produce JSON-parseable error bodies matching
 * the `{"success":false,"error":"..."}` envelope. This is the core-framework
 * fix for #427.
 */
class ToolErrorJsonTest {

    /** Tool with a required param, used to trigger param-extraction errors. */
    private class RequiredParamTool : McpTool() {
        override val name = "required_param_tool"
        override val description = "Tool with a required param"
        val target by stringParam("target", "Required target")
        override suspend fun execute(): ToolResult =
            ToolResult.Success("""{"success":true,"value":"$target"}""")
    }

    /** Tool that always throws, used to trigger the catch-all error path. */
    private class ThrowingTool : McpTool() {
        override val name = "throwing_tool"
        override val description = "Tool that always throws"
        override suspend fun execute(): ToolResult = error("something broke badly")
    }

    /** Tool that throws with a null message. */
    private class NullMessageThrowingTool : McpTool() {
        override val name = "null_msg_tool"
        override val description = "Tool that throws with null message"
        override suspend fun execute(): ToolResult =
            throw object : RuntimeException(null as String?) {}
    }

    @Test
    fun `missing required param produces JSON error envelope`() = runBlocking {
        val tool = RequiredParamTool()
        val result = tool.invoke(buildJsonObject {})

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        val json = Json.parseToJsonElement(error.message).jsonObject
        assertFalse(json["success"]!!.jsonPrimitive.boolean)
        assertTrue(json["error"]!!.jsonPrimitive.content.contains("Missing required parameter"))
        assertTrue(json["error"]!!.jsonPrimitive.content.contains("target"))
    }

    @Test
    fun `unhandled exception produces JSON error envelope`() = runBlocking {
        val tool = ThrowingTool()
        val result = tool.invoke(buildJsonObject {})

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        val json = Json.parseToJsonElement(error.message).jsonObject
        assertFalse(json["success"]!!.jsonPrimitive.boolean)
        assertEquals("something broke badly", json["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `exception with null message uses class name`() = runBlocking {
        val tool = NullMessageThrowingTool()
        val result = tool.invoke(buildJsonObject {})

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        val json = Json.parseToJsonElement(error.message).jsonObject
        assertFalse(json["success"]!!.jsonPrimitive.boolean)
        // Falls back to qualified class name or "unknown error"
        assertTrue(json["error"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `error envelope round-trips through toCallToolResult`() = runBlocking {
        val tool = RequiredParamTool()
        val toolResult = tool.invoke(buildJsonObject {})
        val callResult = toolResult.toCallToolResult()

        assertTrue(callResult.isError == true)
        val text = (callResult.content.first() as TextContent).text!!
        val json = Json.parseToJsonElement(text).jsonObject
        assertFalse(json["success"]!!.jsonPrimitive.boolean)
        assertTrue(json["error"]!!.jsonPrimitive.content.contains("Missing required parameter"))
    }

    @Test
    fun `error message with special characters produces valid JSON`() = runBlocking {
        val tool = object : McpTool() {
            override val name = "special_chars_tool"
            override val description = "Tool that throws with special chars"
            override suspend fun execute(): ToolResult =
                error("""contains "quotes" and \backslash and	tab""")
        }
        val result = tool.invoke(buildJsonObject {})

        assertTrue(result is ToolResult.Error)
        val error = result as ToolResult.Error
        // Must be valid JSON despite special characters in the message
        val json = Json.parseToJsonElement(error.message).jsonObject
        assertFalse(json["success"]!!.jsonPrimitive.boolean)
        assertTrue(json["error"]!!.jsonPrimitive.content.contains("quotes"))
    }

    @Test
    fun `valid args bypass error envelope and return tool result`() = runBlocking {
        val tool = RequiredParamTool()
        val result = tool.invoke(
            buildJsonObject { put("target", JsonPrimitive("hello")) }
        )

        assertTrue(result is ToolResult.Success)
    }
}
