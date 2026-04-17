package com.rousecontext.mcp.tool.params

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A list of string primitives. The more complex list-of-objects case (e.g.
 * notification action buttons) is handled by callers reading the raw JSON
 * themselves via [com.rousecontext.mcp.tool.McpTool.rawArgs].
 */
class ListParam(name: String, description: String) : ParamDef<List<String>>(name, description) {

    fun optional(): ListParam = apply { required = false }

    fun default(value: List<String>): ListParam = apply {
        required = false
        defaultValue = value
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("array"))
        if (description.isNotEmpty()) put("description", JsonPrimitive(description))
        put(
            "items",
            buildJsonObject { put("type", JsonPrimitive("string")) }
        )
    }

    @Suppress("ReturnCount")
    override fun extract(args: JsonObject?): ParamExtract<List<String>> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonArray) {
            return ParamExtract.Error(
                "Parameter '$name' must be an array, got ${describeJsonType(raw)}"
            )
        }
        val out = ArrayList<String>(raw.size)
        for ((i, el) in raw.withIndex()) {
            val p = el as? JsonPrimitive
                ?: return ParamExtract.Error(
                    "Parameter '$name[$i]' must be a string primitive"
                )
            if (!p.isString) {
                return ParamExtract.Error(
                    "Parameter '$name[$i]' must be a string, got ${describeJsonType(el)}"
                )
            }
            out.add(p.content)
        }
        return ParamExtract.Value(out)
    }
}
