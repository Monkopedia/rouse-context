package com.rousecontext.mcp.tool

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `.required()` hands back a non-null delegate, so tool bodies read a plain
 * `String`/`Int`/… instead of `String?` + `!!`. Every assertion below that
 * assigns a param to a non-null local is a *compile-time* check — the test
 * would not build if the delegate still returned a nullable type.
 */
class RequiredParamDelegateTest {

    enum class Mode { FAST, SLOW }

    private class AllTypesTool : McpTool() {
        override val name = "all_types"
        override val description = "reads every required param as non-null"

        val who by stringParam("who", "name").required()
        val count by intParam("count", "how many").required()
        val loud by boolParam("loud", "shout").required()
        val mode by enumParam("mode", "speed", Mode::class).required()
        val nickname by stringParam("nickname", "optional alias").optional()

        override suspend fun execute(): ToolResult {
            // Non-null locals: these lines only compile if the delegates are non-null.
            val name: String = who
            val n: Int = count
            val shout: Boolean = loud
            val m: Mode = mode
            val alias: String? = nickname
            return ToolResult.Success("$name/$n/$shout/${m.name}/$alias")
        }
    }

    private class TracingTool : McpTool() {
        override val name = "tracing"
        override val description = "records whether execute ran"

        var executed = false

        val who by stringParam("who", "name").required()

        override suspend fun execute(): ToolResult {
            executed = true
            return ToolResult.Success(who)
        }
    }

    @Test
    fun `required params read as non-null inside execute`() = runBlocking {
        val tool = AllTypesTool()
        val args = buildJsonObject {
            put("who", JsonPrimitive("ada"))
            put("count", JsonPrimitive(3))
            put("loud", JsonPrimitive(true))
            put("mode", JsonPrimitive("fast"))
        }
        val result = tool.invoke(args)
        assertEquals(ToolResult.Success("ada/3/true/FAST/null"), result)
    }

    @Test
    fun `optional param alongside required params still reads back`() = runBlocking {
        val tool = AllTypesTool()
        val args = buildJsonObject {
            put("who", JsonPrimitive("ada"))
            put("count", JsonPrimitive(3))
            put("loud", JsonPrimitive(false))
            put("mode", JsonPrimitive("slow"))
            put("nickname", JsonPrimitive("A"))
        }
        assertEquals(ToolResult.Success("ada/3/false/SLOW/A"), tool.invoke(args))
    }

    @Test
    fun `missing required param errors before execute runs`() = runBlocking {
        val tool = TracingTool()
        val result = tool.invoke(buildJsonObject {})
        assertTrue("expected an error result, got $result", result is ToolResult.Error)
        assertTrue(
            (result as ToolResult.Error).message,
            result.message.contains("Missing required parameter 'who'")
        )
        assertFalse("execute() must not run when a required param is missing", tool.executed)
    }

    @Test
    fun `required params are still marked required in the schema`() {
        val schema = AllTypesTool().buildSchema()
        assertEquals(listOf("who", "count", "loud", "mode"), schema.required)
    }

    @Test
    fun `required cannot follow optional`() {
        val err = assertThrows(IllegalArgumentException::class.java) { OptionalThenRequiredTool() }
        assertTrue(err.message ?: "", (err.message ?: "").contains("nickname"))
    }

    private class OptionalThenRequiredTool : McpTool() {
        override val name = "bad"
        override val description = "declares a non-null read on an optional param"

        val nickname by stringParam("nickname", "alias").optional().required()

        override suspend fun execute(): ToolResult = ToolResult.Success(nickname)
    }
}
