package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.server.Server

/**
 * Register a tool built with the [McpTool] DSL on an MCP [Server].
 *
 * The [factory] is invoked twice at registration — once to read the
 * (static) name / description / schema, and then once more at each tool call
 * so each invocation sees a fresh instance. This means you can hold
 * per-call state as regular fields on your tool subclass without worrying
 * about concurrent calls stomping on each other.
 *
 * ```kotlin
 * server.registerTool { LaunchAppTool(context, launchNotifier) }
 * ```
 */
fun Server.registerTool(factory: () -> McpTool) {
    // Introspect once at registration time. Building a fresh instance here
    // discovers every delegate-backed ParamDef.
    val prototype = factory()
    val name = prototype.name
    val description = prototype.description
    val schema = prototype.buildSchema()

    addTool(
        name = name,
        description = description,
        inputSchema = schema
    ) { request ->
        val tool = factory()
        tool.invoke(request.params.arguments).toCallToolResult()
    }
}
