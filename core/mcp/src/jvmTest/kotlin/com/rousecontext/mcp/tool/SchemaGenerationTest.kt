package com.rousecontext.mcp.tool

import com.rousecontext.mcp.tool.params.BoolParam
import com.rousecontext.mcp.tool.params.EnumParam
import com.rousecontext.mcp.tool.params.InstantParam
import com.rousecontext.mcp.tool.params.IntParam
import com.rousecontext.mcp.tool.params.ListParam
import com.rousecontext.mcp.tool.params.MapParam
import com.rousecontext.mcp.tool.params.StringParam
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class SchemaGenerationTest {

    private val json = Json { prettyPrint = false }

    @Test
    fun `string schema contains type and description`() {
        val s = StringParam("x", "my desc").schema()
        assertEquals("string", s["type"]!!.jsonPrimitive.content)
        assertEquals("my desc", s["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun `string with choices emits enum`() {
        val s = StringParam("x", "d").choices("a", "b", "c").schema()
        val values = s["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("a", "b", "c"), values)
    }

    @Test
    fun `int with range emits minimum and maximum`() {
        val s = IntParam("x", "d").range(0..5).schema()
        assertEquals("integer", s["type"]!!.jsonPrimitive.content)
        assertEquals(0, s["minimum"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, s["maximum"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `bool schema`() {
        val s = BoolParam("x", "d").schema()
        assertEquals("boolean", s["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `bool default emits default`() {
        val s = BoolParam("x", "d").default(true).schema()
        assertEquals(true, s["default"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `enum schema emits string enum values`() {
        val s = EnumParam("c", "d", ParamExtractionTest.Color::class).schema()
        assertEquals("string", s["type"]!!.jsonPrimitive.content)
        val values = s["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(setOf("red", "blue"), values.toSet())
    }

    @Test
    fun `map schema emits object with string additionalProperties`() {
        val s = MapParam("m", "d").schema()
        assertEquals("object", s["type"]!!.jsonPrimitive.content)
        assertEquals(
            "string",
            s["additionalProperties"]!!.jsonObject["type"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `list schema emits array with string items`() {
        val s = ListParam("xs", "d").schema()
        assertEquals("array", s["type"]!!.jsonPrimitive.content)
        assertEquals("string", s["items"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `instant schema uses date-time format`() {
        val s = InstantParam("t", "d").schema()
        assertEquals("string", s["type"]!!.jsonPrimitive.content)
        assertEquals("date-time", s["format"]!!.jsonPrimitive.content)
    }

    @Test
    fun `required list matches non-optional params`() {
        val t = object : McpTool() {
            override val name = "probe"
            override val description = "desc"
            val a by stringParam("a", "d")
            val b by stringParam("b", "d").optional()
            val c by intParam("c", "d").default(7)
            override suspend fun execute(): ToolResult = ToolResult.Success("ok")
        }
        val schema = t.buildSchema()
        assertEquals(listOf("a"), schema.required)
        val names = schema.properties!!.keys
        assertEquals(setOf("a", "b", "c"), names)
    }

    /** Golden-file style assertion: full tool schema emitted is stable. */
    @Test
    fun `golden schema for launch-app shape`() {
        val t = object : McpTool() {
            override val name = "launch_app"
            override val description = "Launch an installed app by package name"
            val packageName by stringParam("package_name", "Package name, e.g. com.example.app")
            val extras by mapParam("extras", "String-valued intent extras").optional()
            override suspend fun execute(): ToolResult = ToolResult.Success("ok")
        }
        val expected =
            """{"package_name":{"type":"string",""" +
                """"description":"Package name, e.g. com.example.app"},""" +
                """"extras":{"type":"object","description":"String-valued intent extras",""" +
                """"additionalProperties":{"type":"string"}}}"""
        assertEquals(expected, t.buildSchema().properties.toString())
        assertEquals(listOf("package_name"), t.buildSchema().required)
    }
}
