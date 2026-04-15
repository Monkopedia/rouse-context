package com.rousecontext.mcp.tool

import com.rousecontext.mcp.tool.params.BoolParam
import com.rousecontext.mcp.tool.params.EnumParam
import com.rousecontext.mcp.tool.params.InstantParam
import com.rousecontext.mcp.tool.params.IntParam
import com.rousecontext.mcp.tool.params.ListParam
import com.rousecontext.mcp.tool.params.MapParam
import com.rousecontext.mcp.tool.params.ParamExtract
import com.rousecontext.mcp.tool.params.StringParam
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers every param type: valid, missing (required/optional), type mismatch, range/choices, null handling. */
class ParamExtractionTest {

    private fun <T> Any?.valueOr(default: Nothing? = null): T? {
        val r = this as ParamExtract<*>
        return when (r) {
            is ParamExtract.Value<*> ->
                @Suppress("UNCHECKED_CAST")
                (r.value as T?)
            is ParamExtract.Error -> error(r.message)
        }
    }

    private fun Any?.error(): String {
        val r = this as ParamExtract<*>
        return (r as ParamExtract.Error).message
    }

    // -------- StringParam --------

    @Test
    fun `string extract valid`() {
        val p = StringParam("x", "desc")
        val args = buildJsonObject { put("x", JsonPrimitive("hi")) }
        assertEquals("hi", p.extract(args).valueOr<String>())
    }

    @Test
    fun `string missing required errors`() {
        val p = StringParam("x", "desc")
        val msg = p.extract(buildJsonObject {}).error()
        assertTrue(msg, msg.contains("Missing required parameter 'x'"))
    }

    @Test
    fun `string missing optional returns null`() {
        val p = StringParam("x", "d").optional()
        assertNull(p.extract(buildJsonObject {}).valueOr<String>())
    }

    @Test
    fun `string default used when missing`() {
        val p = StringParam("x", "d").default("fallback")
        assertEquals("fallback", p.extract(buildJsonObject {}).valueOr<String>())
    }

    @Test
    fun `string wrong type errors`() {
        val p = StringParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive(42)) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be a string") && msg.contains("number"))
    }

    @Test
    fun `string null treated as missing`() {
        val p = StringParam("x", "d")
        val args = buildJsonObject { put("x", JsonNull) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("Missing required"))
    }

    @Test
    fun `string choices violation errors`() {
        val p = StringParam("x", "d").choices("a", "b")
        val args = buildJsonObject { put("x", JsonPrimitive("c")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be one of"))
    }

    // -------- IntParam --------

    @Test
    fun `int extract valid`() {
        val p = IntParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive(7)) }
        assertEquals(7, p.extract(args).valueOr<Int>())
    }

    @Test
    fun `int range violation errors`() {
        val p = IntParam("x", "d").range(0..10)
        val args = buildJsonObject { put("x", JsonPrimitive(20)) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be in range 0..10"))
    }

    @Test
    fun `int wrong type errors`() {
        val p = IntParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive("nope")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be an integer"))
    }

    @Test
    fun `int default used when missing`() {
        val p = IntParam("x", "d").default(5)
        assertEquals(5, p.extract(buildJsonObject {}).valueOr<Int>())
    }

    // -------- BoolParam --------

    @Test
    fun `bool extract valid true`() {
        val p = BoolParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive(true)) }
        assertEquals(true, p.extract(args).valueOr<Boolean>())
    }

    @Test
    fun `bool extract string coded`() {
        val p = BoolParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive("false")) }
        assertEquals(false, p.extract(args).valueOr<Boolean>())
    }

    @Test
    fun `bool wrong string errors`() {
        val p = BoolParam("x", "d")
        val args = buildJsonObject { put("x", JsonPrimitive("maybe")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be a boolean"))
    }

    // -------- EnumParam --------

    enum class Color { RED, BLUE }

    @Test
    fun `enum extract case insensitive`() {
        val p = EnumParam("c", "d", Color::class)
        val args = buildJsonObject { put("c", JsonPrimitive("Red")) }
        assertEquals(Color.RED, p.extract(args).valueOr<Color>())
    }

    @Test
    fun `enum invalid choice errors`() {
        val p = EnumParam("c", "d", Color::class)
        val args = buildJsonObject { put("c", JsonPrimitive("green")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be one of"))
    }

    // -------- MapParam --------

    @Test
    fun `map extract valid`() {
        val p = MapParam("m", "d")
        val args = buildJsonObject {
            put("m", buildJsonObject { put("k", JsonPrimitive("v")) })
        }
        val out: Map<String, String> = p.extract(args).valueOr()!!
        assertEquals(mapOf("k" to "v"), out)
    }

    @Test
    fun `map wrong type errors`() {
        val p = MapParam("m", "d")
        val args = buildJsonObject { put("m", JsonPrimitive("nope")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("must be an object"))
    }

    // -------- ListParam --------

    @Test
    fun `list extract valid`() {
        val p = ListParam("xs", "d")
        val args = buildJsonObject {
            put(
                "xs",
                buildJsonArray {
                    add(JsonPrimitive("a"))
                    add(JsonPrimitive("b"))
                }
            )
        }
        assertEquals(listOf("a", "b"), p.extract(args).valueOr<List<String>>())
    }

    @Test
    fun `list non-string element errors`() {
        val p = ListParam("xs", "d")
        val args = buildJsonObject {
            put("xs", buildJsonArray { add(JsonPrimitive(1)) })
        }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("xs[0]"))
    }

    // -------- InstantParam --------

    @Test
    fun `instant extract valid`() {
        val p = InstantParam("t", "d")
        val args = buildJsonObject { put("t", JsonPrimitive("2024-01-01T00:00:00Z")) }
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), p.extract(args).valueOr<Instant>())
    }

    @Test
    fun `instant invalid string errors`() {
        val p = InstantParam("t", "d")
        val args = buildJsonObject { put("t", JsonPrimitive("not-a-date")) }
        val msg = p.extract(args).error()
        assertTrue(msg, msg.contains("ISO-8601"))
    }

    @Test
    fun `instant missing optional returns null`() {
        val p = InstantParam("t", "d").optional()
        assertNull(p.extract(buildJsonObject {}).valueOr<Instant>())
    }
}
