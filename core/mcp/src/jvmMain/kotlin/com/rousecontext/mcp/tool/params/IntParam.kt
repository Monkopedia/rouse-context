package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class IntParam(name: String, description: String) : ParamDef<Int>(name, description) {
    private var range: IntRange? = null

    fun optional(): IntParam = apply { required = false }

    fun default(value: Int): IntParam = apply {
        required = false
        defaultValue = value
    }

    fun range(r: IntRange): IntParam = apply { range = r }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
        range?.let {
            put("minimum", JsonPrimitive(it.first))
            put("maximum", JsonPrimitive(it.last))
        }
        defaultValue?.let { put("default", JsonPrimitive(it)) }
    }

    @Suppress("ReturnCount")
    override fun extract(args: JsonObject?): ParamExtract<Int> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonPrimitive || raw.isString) {
            return ParamExtract.Error(
                "Parameter '$name' must be an integer, got ${describeJsonType(raw)}"
            )
        }
        val asLong = raw.content.toLongOrNull()
            ?: return ParamExtract.Error(
                "Parameter '$name' must be an integer, got '${raw.content}'"
            )
        if (asLong < Int.MIN_VALUE.toLong() || asLong > Int.MAX_VALUE.toLong()) {
            return ParamExtract.Error("Parameter '$name' out of Int range: $asLong")
        }
        val value = asLong.toInt()
        range?.let {
            if (value !in it) {
                return ParamExtract.Error(
                    "Parameter '$name' must be in range ${it.first}..${it.last}, got $value"
                )
            }
        }
        return ParamExtract.Value(value)
    }
}
