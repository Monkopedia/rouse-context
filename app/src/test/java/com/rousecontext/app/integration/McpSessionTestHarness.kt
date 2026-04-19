package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.mcp.core.INTERNAL_TOKEN_HEADER
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.TunnelState
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Batch B (issue #252) drivers that feed an MCP conversation into the
 * device's real [McpSession] Ktor server over loopback TCP.
 *
 * ## Why loopback instead of the relay?
 *
 * The "AI client → fixture relay → mux → [com.rousecontext.bridge.SessionHandler]
 *  → [McpSession]" path is the production shape, and the scenarios in #252
 * are *about* what happens on the device side of that pipe: audit DB
 * rows, session-summary notifications, per-call notifications, tool
 * dispatch. That's all downstream of the TLS + mux + bridge layers.
 *
 * Robolectric ships Conscrypt as the default TLS provider, and its client
 * sockets silently drop the SNI extension from the ClientHello (see
 * #262). The relay's passthrough router rejects connections without SNI,
 * so a synthetic AI client can't reach the device's bridge at all under
 * Robolectric — independently of `setHostname` /
 * `SSLParameters.serverNames` / `Conscrypt.setHostname` / the
 * `setUseEngineSocket(false)` legacy path.
 *
 * Batch B's assertions only care about the device-side half of the pipe,
 * so we skip the relay + bridge layers entirely and call
 * [McpSession]'s local HTTP server directly. The bridge layer is already
 * end-to-end regression-guarded by the JVM-only
 * [com.rousecontext.tunnel.integration.ClaudeFullFlowEndToEndTest] and
 * [com.rousecontext.bridge.SessionHandlerTest]; batch C (#253) picks up
 * the resilience scenarios once #262 is resolved.
 *
 * ## How it works
 *
 * The Koin graph's [McpSession] binds to an ephemeral loopback port at
 * start and exposes its own [McpSession.internalToken] (see issue #177).
 * The bridge normally injects that token on every inbound request as
 * `X-Internal-Token`; here we do the same directly from the test.
 *
 * [McpTestDriver.doMcpCall] speaks raw HTTP/1.1 over [Socket] (no TLS,
 * no SNI — the server is already plaintext on `127.0.0.1`) and returns
 * the body + headers. Each call shares the same socket so the
 * `Mcp-Session-Id` captured from `initialize` round-trips correctly.
 */
class McpTestDriver(
    private val session: McpSession,
    private val hostHeader: String,
    private val bearerToken: String
) : AutoCloseable {

    private val socket: Socket = Socket("127.0.0.1", session.port)
    private val input = socket.inputStream
    private val output = socket.outputStream
    private var mcpSessionId: String? = null

    init {
        socket.soTimeout = DEFAULT_SO_TIMEOUT_MS
    }

    /** Initialize the MCP session and capture `mcp-session-id`. */
    fun initialize() {
        val body = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":""" +
            """{"protocolVersion":"2025-03-26","capabilities":{},""" +
            """"clientInfo":{"name":"synth","version":"1.0"}}}"""
        val response = doMcpCall("/mcp", body)
        require(response.statusCode == 200) {
            "initialize should be 200, got ${response.statusCode}: ${response.body}"
        }
        mcpSessionId = response.headers["mcp-session-id"]
            ?: error("initialize must set mcp-session-id header")
        val notified = doMcpCall(
            "/mcp",
            """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
        )
        require(notified.statusCode in 200..299) {
            "notifications/initialized should succeed, got ${notified.statusCode}"
        }
    }

    /** Call a tool by name. Returns the raw response body. */
    fun callTool(name: String, argumentsJson: String = "{}", id: Int = 2): String {
        val body = """{"jsonrpc":"2.0","id":$id,"method":"tools/call",""" +
            """"params":{"name":"$name","arguments":$argumentsJson}}"""
        val response = doMcpCall("/mcp", body)
        require(response.statusCode == 200) {
            "tools/call should be 200, got ${response.statusCode}: ${response.body}"
        }
        return response.body
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private data class McpHttpResponse(
        val statusCode: Int,
        val headers: Map<String, String>,
        val body: String
    )

    @Suppress("LongMethod", "CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun doMcpCall(path: String, body: String): McpHttpResponse {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val sb = StringBuilder()
        sb.append("POST $path HTTP/1.1\r\n")
        sb.append("Host: $hostHeader\r\n")
        sb.append("Content-Type: application/json\r\n")
        sb.append("Content-Length: ${bodyBytes.size}\r\n")
        sb.append("Connection: keep-alive\r\n")
        sb.append("$INTERNAL_TOKEN_HEADER: ${session.internalToken}\r\n")
        sb.append("Authorization: Bearer $bearerToken\r\n")
        mcpSessionId?.let { sb.append("Mcp-Session-Id: $it\r\n") }
        sb.append("\r\n")
        output.write(sb.toString().toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()

        val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))
        val statusLine = reader.readLine() ?: error("no status line for $path")
        require(statusLine.startsWith("HTTP/1.1")) { "bad status line: $statusLine" }
        val statusCode = statusLine.split(" ")[1].toInt()

        val headers = mutableMapOf<String, String>()
        var contentLength = -1
        var chunked = false
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0) {
                headers[line.substring(0, colonIdx).lowercase()] =
                    line.substring(colonIdx + 1).trim()
            }
            val lc = line.lowercase()
            if (lc.startsWith("content-length:")) {
                contentLength = lc.substringAfter(":").trim().toInt()
            }
            if (lc.startsWith("transfer-encoding:") && lc.contains("chunked")) {
                chunked = true
            }
        }

        val body = when {
            contentLength > 0 -> {
                val buf = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(buf, read, contentLength - read)
                    if (n == -1) break
                    read += n
                }
                String(buf, 0, read)
            }
            chunked -> readChunkedBody(reader)
            else -> ""
        }
        return McpHttpResponse(statusCode, headers, body)
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun readChunkedBody(reader: BufferedReader): String {
        val sb = StringBuilder()
        while (true) {
            val sizeLine = reader.readLine() ?: break
            val chunkSize = sizeLine.trim().toInt(16)
            if (chunkSize == 0) {
                reader.readLine()
                break
            }
            val buf = CharArray(chunkSize)
            var read = 0
            while (read < chunkSize) {
                val n = reader.read(buf, read, chunkSize - read)
                if (n == -1) break
                read += n
            }
            sb.append(buf, 0, read)
            reader.readLine()
        }
        return sb.toString()
    }

    companion object {
        private const val DEFAULT_SO_TIMEOUT_MS = 15_000
    }
}

/**
 * Convenience: enable every integration in [integrationIds] on the real
 * [IntegrationStateStore] so [com.rousecontext.app.registry.IntegrationProviderRegistry]
 * returns a non-null provider for each.
 */
suspend fun AppIntegrationTestHarness.enableIntegrations(integrationIds: List<String>) {
    val stateStore: IntegrationStateStore = koin.get()
    integrationIds.forEach { stateStore.setUserEnabled(it, true) }
    // Give the registry's reactive combine() one dispatcher tick to observe.
    kotlinx.coroutines.yield()
}

/**
 * Drive [com.rousecontext.tunnel.TunnelState] transitions to the notifier
 * observers.
 *
 * - [tunnelStateFlow] feeds [com.rousecontext.notifications.SessionSummaryNotifier.observe]
 *   so scenarios can drive ACTIVE → CONNECTED (stream drain) in the exact
 *   moment the assertions need, without depending on a real tunnel.
 */
class TestTunnelState {
    val tunnelStateFlow: MutableStateFlow<TunnelState> =
        MutableStateFlow(TunnelState.DISCONNECTED)

    /** Simulate "tunnel fully up, stream opened". */
    fun markActive() {
        tunnelStateFlow.value = TunnelState.CONNECTED
        tunnelStateFlow.value = TunnelState.ACTIVE
    }

    /** Simulate "all streams drained while tunnel stays up". */
    fun markDrained() {
        tunnelStateFlow.value = TunnelState.CONNECTED
    }

    fun markDisconnected() {
        tunnelStateFlow.value = TunnelState.DISCONNECTED
    }
}
