package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Map of string to string — matches the common "extras" / headers pattern.
 * Values that aren't string primitives are coerced to their `.content` form.
 */
class MapParam(name: String, description: String) :
    ParamDef<Map<String, String>>(
        name,
        description
    ) {

    fun optional(): MapParam = apply { required = false }

    fun default(value: Map<String, String>): MapParam = apply {
        required = false
        defaultValue = value
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("object"))
        put("description", JsonPrimitive(description))
        put(
            "additionalProperties",
            buildJsonObject { put("type", JsonPrimitive("string")) }
        )
    }

    @Suppress("ReturnCount")
    override fun extract(args: JsonObject?): ParamExtract<Map<String, String>> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonObject) {
            return ParamExtract.Error(
                "Parameter '$name' must be an object, got ${describeJsonType(raw)}"
            )
        }
        val result = mutableMapOf<String, String>()
        for ((k, v) in raw) {
            val primitive = v as? JsonPrimitive
                ?: return ParamExtract.Error(
                    "Parameter '$name.$k' must be a string primitive"
                )
            result[k] = primitive.content
        }
        return ParamExtract.Value(result)
    }
}
