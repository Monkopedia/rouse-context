package com.rousecontext.mcp.tool

import com.rousecontext.mcp.tool.params.BoolParam
import com.rousecontext.mcp.tool.params.EnumParam
import com.rousecontext.mcp.tool.params.InstantParam
import com.rousecontext.mcp.tool.params.IntParam
import com.rousecontext.mcp.tool.params.ListParam
import com.rousecontext.mcp.tool.params.MapParam
import com.rousecontext.mcp.tool.params.ParamDef
import com.rousecontext.mcp.tool.params.ParamExtract
import com.rousecontext.mcp.tool.params.StringParam
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KClass
import kotlin.reflect.KProperty
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Base class for tools. A tool authored against [McpTool] declares its name,
 * description, and typed parameters via delegate helpers, then overrides
 * [execute] with the business logic.
 *
 * Instances are cheap — the framework creates a fresh one per call, so it is
 * safe to hold request-scoped state as regular fields. Do not share instances
 * across calls.
 *
 * See `core/mcp/src/jvmMain/kotlin/com/rousecontext/mcp/tool/README.md` for
 * the full authoring guide.
 */
abstract class McpTool {
    abstract val name: String
    abstract val description: String

    private val params = mutableListOf<ParamBinding<*>>()

    /** Set by [invoke] before [execute] runs, so param delegates can read it. */
    private var boundArgs: JsonObject? = null

    /** Raw JSON arguments for tools that need to reach beyond the typed params. */
    protected val rawArgs: JsonObject?
        get() = boundArgs

    // ---------- param declarations ----------

    protected fun stringParam(name: String, description: String): StringParam =
        StringParam(name, description)

    protected fun intParam(name: String, description: String): IntParam =
        IntParam(name, description)

    protected fun boolParam(name: String, description: String): BoolParam =
        BoolParam(name, description)

    protected fun <T : Enum<T>> enumParam(
        name: String,
        description: String,
        kClass: KClass<T>
    ): EnumParam<T> = EnumParam(name, description, kClass)

    protected fun mapParam(name: String, description: String): MapParam =
        MapParam(name, description)

    protected fun listParam(name: String, description: String): ListParam =
        ListParam(name, description)

    protected fun instantParam(name: String, description: String): InstantParam =
        InstantParam(name, description)

    // ---------- delegate wiring ----------

    /**
     * Binding of a [ParamDef] to a concrete property. Holds the most recent
     * extracted value so [getValue] can return it.
     */
    private class ParamBinding<T>(val propertyName: String, val param: ParamDef<T>) :
        ReadOnlyProperty<McpTool, T?> {
        var cached: T? = null
        override fun getValue(thisRef: McpTool, property: KProperty<*>): T? = cached
    }

    /**
     * `val packageName by stringParam(...)` — captured here. We register the
     * binding, then hand back a [ReadOnlyProperty] that reads the cached value.
     */
    protected operator fun <T, P : ParamDef<T>> P.provideDelegate(
        thisRef: McpTool,
        property: KProperty<*>
    ): ReadOnlyProperty<McpTool, T?> {
        val binding = ParamBinding(property.name, this)
        thisRef.params.add(binding)
        return binding
    }

    // ---------- framework-internal ----------

    fun buildSchema(): ToolSchema {
        val properties = buildJsonObject {
            for (binding in params) {
                put(binding.param.name, binding.param.schema())
            }
        }
        val required = params
            .filter { it.param.required }
            .map { it.param.name }
        return ToolSchema(properties = properties, required = required)
    }

    fun paramNames(): List<String> = params.map { it.param.name }

    /**
     * Extract all params, populate bindings, and run [execute]. Any exception
     * thrown by [execute] is caught and converted to [ToolResult.Error] so one
     * misbehaving tool cannot kill the MCP session.
     */
    suspend fun invoke(args: JsonObject?): ToolResult {
        boundArgs = args
        for (binding in params) {
            @Suppress("UNCHECKED_CAST")
            val b = binding as ParamBinding<Any>
            when (val ex = b.param.extract(args)) {
                is ParamExtract.Value -> b.cached = ex.value
                is ParamExtract.Error -> return ToolResult.Error(ex.message)
            }
        }
        return try {
            execute()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (
            @Suppress("TooGenericExceptionCaught")
            t: Throwable
        ) {
            ToolResult.Error(t.message ?: t::class.qualifiedName ?: "unknown error")
        }
    }

    abstract suspend fun execute(): ToolResult
}
