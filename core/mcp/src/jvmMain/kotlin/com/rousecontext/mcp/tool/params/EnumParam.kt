package com.rousecontext.mcp.tool.params

import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

class EnumParam<T : Enum<T>>(name: String, description: String, private val kClass: KClass<T>) :
    ParamDef<T>(name, description) {

    private val values: Array<T> = kClass.java.enumConstants
        ?: error("EnumParam requires an enum class, got ${kClass.qualifiedName}")

    fun optional(): EnumParam<T> = apply { required = false }

    fun default(value: T): EnumParam<T> = apply {
        required = false
        defaultValue = value
    }

    override fun schema(): JsonObject = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
        put("enum", JsonArray(values.map { JsonPrimitive(it.name.lowercase()) }))
        defaultValue?.let { put("default", JsonPrimitive(it.name.lowercase())) }
    }

    @Suppress("ReturnCount")
    override fun extract(args: JsonObject?): ParamExtract<T> {
        val raw = rawValue(args, name) ?: run {
            if (required) return ParamExtract.Error("Missing required parameter '$name'")
            return ParamExtract.Value(defaultValue)
        }
        if (raw !is JsonPrimitive || !raw.isString) {
            return ParamExtract.Error(
                "Parameter '$name' must be a string, got ${describeJsonType(raw)}"
            )
        }
        val str = raw.content.lowercase()
        val match = values.firstOrNull { it.name.lowercase() == str }
            ?: return ParamExtract.Error(
                "Parameter '$name' must be one of " +
                    values.joinToString(", ") { it.name.lowercase() } +
                    ", got '${raw.content}'"
            )
        return ParamExtract.Value(match)
    }
}
