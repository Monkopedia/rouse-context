package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A named, typed parameter attached to an [com.rousecontext.mcp.tool.McpTool].
 *
 * One [ParamDef] instance describes one JSON property in the tool's input schema
 * and knows how to extract a value of type [T] from the arguments supplied to a
 * tool call. Instances are bound to Kotlin properties via [provideDelegate] so
 * tool authors can write `val foo by stringParam("foo", "...")` and read the
 * extracted value transparently during [com.rousecontext.mcp.tool.McpTool.execute].
 */
abstract class ParamDef<T>(val name: String, val description: String) {
    /** Whether a missing value should produce an error. Flipped by [optional]. */
    var required: Boolean = true
        protected set

    /** Default value when optional and missing. Null means "no default". */
    var defaultValue: T? = null
        protected set

    /** JSON schema fragment for this parameter. Composed into the tool schema. */
    abstract fun schema(): JsonObject

    /**
     * Extract the typed value from the raw arguments, or return a
     * [ParamExtract.Error] if missing/invalid.
     */
    abstract fun extract(args: JsonObject?): ParamExtract<T>
}

sealed class ParamExtract<out T> {
    data class Value<T>(val value: T?) : ParamExtract<T>()
    data class Error(val message: String) : ParamExtract<Nothing>()
}

/**
 * Fetch `args[name]`, treating `JsonNull` as missing.
 */
internal fun rawValue(args: JsonObject?, name: String): JsonElement? {
    val raw = args?.get(name) ?: return null
    if (raw is kotlinx.serialization.json.JsonNull) return null
    return raw
}
