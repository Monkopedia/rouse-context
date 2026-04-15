package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class BoolParam(name: String, description: String) : ParamDef<Boolean>(name, description) {

    fun optional(): BoolParam = apply { required = false }

    fun default(value: Boolean): BoolParam = apply {
        required = false
        defaultValue = value
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
        defaultValue?.let { put("default", JsonPrimitive(it)) }
    }

    override fun extract(args: JsonObject?): ParamExtract<Boolean> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonPrimitive) {
            return ParamExtract.Error(
                "Parameter '$name' must be a boolean, got ${describeJsonType(raw)}"
            )
        }
        // Accept both true-boolean primitives ("true"/"false" with !isString) and
        // string-coded booleans (client serializer may quote them).
        val parsed = when {
            !raw.isString -> raw.content.toBooleanStrictOrNull()
            else -> raw.content.toBooleanStrictOrNull()
        } ?: return ParamExtract.Error(
            "Parameter '$name' must be a boolean, got '${raw.content}'"
        )
        return ParamExtract.Value(parsed)
    }
}
