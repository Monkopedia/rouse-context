package com.rousecontext.mcp.tool.params

import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * ISO-8601 timestamp parameter, parsed via [java.time.Instant.parse].
 */
class InstantParam(name: String, description: String) : ParamDef<Instant>(name, description) {

    fun optional(): InstantParam = apply { required = false }

    fun default(value: Instant): InstantParam = apply {
        required = false
        defaultValue = value
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("format", JsonPrimitive("date-time"))
        put("description", JsonPrimitive(description))
        defaultValue?.let { put("default", JsonPrimitive(it.toString())) }
    }

    override fun extract(args: JsonObject?): ParamExtract<Instant> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonPrimitive || !raw.isString) {
            return ParamExtract.Error(
                "Parameter '$name' must be an ISO-8601 string, got ${describeJsonType(raw)}"
            )
        }
        return try {
            ParamExtract.Value(Instant.parse(raw.content))
        } catch (e: DateTimeParseException) {
            ParamExtract.Error(
                "Parameter '$name' must be a valid ISO-8601 instant, got '${raw.content}'"
            )
        }
    }
}
