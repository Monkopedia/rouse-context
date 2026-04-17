package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class StringParam(name: String, description: String) : ParamDef<String>(name, description) {
    private var choices: List<String>? = null

    fun optional(): StringParam = apply { required = false }

    fun default(value: String): StringParam = apply {
        required = false
        defaultValue = value
    }

    fun choices(vararg values: String): StringParam = apply {
        require(values.isNotEmpty()) { "choices() requires at least one value" }
        choices = values.toList()
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        choices?.let { put("enum", JsonArray(it.map { v -> JsonPrimitive(v) })) }
        defaultValue?.let { put("default", JsonPrimitive(it)) }
    }

    @Suppress("ReturnCount")
    override fun extract(args: JsonObject?): ParamExtract<String> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonPrimitive || !raw.isString) {
            val typeName = describeJsonType(raw)
            return ParamExtract.Error(
                "Parameter '$name' must be a string, got $typeName"
            )
        }
        val str = raw.content
        val allowed = choices
        if (allowed != null && str !in allowed) {
            return ParamExtract.Error(
                "Parameter '$name' must be one of ${allowed.joinToString(", ")}, got '$str'"
            )
        }
        return ParamExtract.Value(str)
    }
}

internal fun describeJsonType(el: kotlinx.serialization.json.JsonElement): String = when (el) {
    is JsonNull -> "null"
    is JsonPrimitive -> when {
        el.isString -> "string"
        el.booleanOrNullTyped() != null -> "boolean"
        el.content.toLongOrNull() != null || el.content.toDoubleOrNull() != null -> "number"
        else -> "primitive"
    }
    is JsonObject -> "object"
    is JsonArray -> "array"
}

private fun JsonPrimitive.booleanOrNullTyped(): Boolean? =
    if (isString) null else content.toBooleanStrictOrNull()
