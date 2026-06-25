package com.rousecontext.app.integration

import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.mcp.core.INTERNAL_TOKEN_HEADER
import com.rousecontext.mcp.core.McpSession
import com.rousecontext.tunnel.TunnelState
import com.rousecontext.tunnel.integration.IntegrationHttpSupport
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
 * Historically (pre-#262) Robolectric's Conscrypt provider silently
 * stripped SNI from every outbound ClientHello, which blocked any
 * attempt to route a synthetic AI client through the real relay's
 * SNI-routed passthrough. Batch B landed against this loopback driver
 * to make progress; [ToolCallViaSniPassthroughTest] now proves the
 * SNI path works end-to-end (via BouncyCastle JSSE swap in
 * [AppIntegrationTestHarness]). The loopback scenarios here stay as-is
 * because their assertions only care about the device-side half of the
 * pipe — the relay + bridge legs are regression-guarded by the JVM-only
 * [com.rousecontext.tunnel.integration.ClaudeFullFlowEndToEndTest] and
 * [com.rousecontext.bridge.SessionHandlerTest].
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
        IntegrationHttpSupport.applyReadTimeout(socket)
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
        IntegrationHttpSupport.release(input)
        runCatching { socket.close() }
    }

    /**
     * Speak one raw HTTP/1.1 round trip over the keep-alive socket via the
     * shared, byte-exact [IntegrationHttpSupport] framing (#523). The helper
     * reuses one buffered byte stream per socket, so sequential calls here stay
     * aligned even when responses are coalesced into one TCP segment.
     */
    private fun doMcpCall(path: String, body: String): IntegrationHttpSupport.HttpResponse {
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
        return IntegrationHttpSupport.exchange(input, output, sb.toString(), bodyBytes)
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
