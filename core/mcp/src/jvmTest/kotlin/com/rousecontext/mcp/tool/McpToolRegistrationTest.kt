package com.rousecontext.mcp.tool

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolRegistrationTest {

    private fun makeServer(): Server = Server(
        Implementation("test", "0.1"),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false)
            )
        )
    )

    private class CountingTool(private val counter: AtomicInteger) : McpTool() {
        override val name = "count"
        override val description = "increment and return the counter"
        val prefix by stringParam("prefix", "label").optional()
        override suspend fun execute(): ToolResult {
            val v = counter.incrementAndGet()
            return ToolResult.Success("${prefix ?: "count"}=$v")
        }
    }

    @Test
    fun `factory is called once per invocation so no state bleed`() = runBlocking {
        val factoryCalls = AtomicInteger(0)
        val counter = AtomicInteger(0)
        val server = makeServer()
        server.registerTool {
            factoryCalls.incrementAndGet()
            CountingTool(counter)
        }
        // One factory call at registration (schema introspection).
        assertEquals(1, factoryCalls.get())
        val registered = server.tools["count"]
        assertNotNull(registered)

        val handler = registered!!.handler
        // Invoke twice — factory must produce a fresh instance each time.
        val r1 = handler.invoke(
            StubClientConnection,
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "count",
                    arguments = buildJsonObject { put("prefix", JsonPrimitive("a")) }
                )
            )
        )
        val r2 = handler.invoke(
            StubClientConnection,
            io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = "count",
                    arguments = buildJsonObject { put("prefix", JsonPrimitive("b")) }
                )
            )
        )
        assertEquals("a=1", (r1.content.first() as TextContent).text)
        assertEquals("b=2", (r2.content.first() as TextContent).text)
        assertEquals(3, factoryCalls.get())
    }

    @Test
    fun `exception in execute becomes error result`() = runBlocking {
        class BoomTool : McpTool() {
            override val name = "boom"
            override val description = "d"
            override suspend fun execute(): ToolResult {
                error("kaboom")
            }
        }
        val tool = BoomTool()
        val result = tool.invoke(null)
        assertTrue(result is ToolResult.Error)
        val err = result as ToolResult.Error
        assertTrue(err.message.contains("kaboom"))
    }

    @Test
    fun `cancellation propagates through execute`() = runBlocking {
        class SlowTool(val started: CompletableDeferred<Unit>) : McpTool() {
            override val name = "slow"
            override val description = "d"
            override suspend fun execute(): ToolResult {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val started = CompletableDeferred<Unit>()
        val tool = SlowTool(started)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.async { tool.invoke(null) }
        started.await()
        job.cancel()
        var gotCancelled = false
        try {
            job.await()
        } catch (_: kotlinx.coroutines.CancellationException) {
            gotCancelled = true
        }
        assertTrue(gotCancelled)
    }

    @Test
    fun `missing required param returns extraction error without calling execute`() = runBlocking {
        var executeCalled = false
        class EchoTool : McpTool() {
            override val name = "echo"
            override val description = "d"
            val s by stringParam("s", "value")
            override suspend fun execute(): ToolResult {
                executeCalled = true
                return ToolResult.Success("ignored")
            }
        }
        val tool = EchoTool()
        val result = tool.invoke(buildJsonObject {})
        assertTrue(result is ToolResult.Error)
        assertTrue((result as ToolResult.Error).message.contains("Missing required parameter 's'"))
        assertEquals(false, executeCalled)
    }
}
