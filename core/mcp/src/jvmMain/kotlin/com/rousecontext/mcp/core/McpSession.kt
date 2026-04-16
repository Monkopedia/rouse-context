package com.rousecontext.mcp.core

import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CompletableDeferred

/**
 * Orchestrates MCP HTTP sessions with per-integration OAuth and auth.
 *
 * Wraps a Ktor embedded HTTP server that serves a single integration.
 * Each integration gets its own hostname, so each McpSession serves one
 * integration with its own OAuth endpoints, device codes, and tokens.
 *
 * The session is long-lived and shared across streams. Provider changes
 * (enable/disable) are reflected immediately via [ProviderRegistry].
 *
 * Usage:
 * ```
 * val session = McpSession(
 *     registry = providerRegistry,
 *     tokenStore = roomTokenStore,
 *     auditListener = auditLog,
 *     hostname = "brave-health.abc123.rousecontext.com",
 *     integration = "health"
 * )
 * session.start(port = 0) // starts embedded HTTP server
 * session.awaitClose()    // suspends until stopped
 * ```
 */
@Suppress("LongParameterList")
class McpSession(
    private val registry: ProviderRegistry,
    private val tokenStore: TokenStore,
    val deviceCodeManager: DeviceCodeManager = DeviceCodeManager(tokenStore = tokenStore),
    val authorizationCodeManager: AuthorizationCodeManager =
        AuthorizationCodeManager(tokenStore = tokenStore),
    private val auditListener: AuditListener? = null,
    private val hostname: String = "localhost",
    private val integration: String = "health",
    private val rateLimiter: RateLimiter? = defaultOAuthInitRateLimiter(),
    private val mcpRateLimiter: RateLimiter? = null,
    private val securityAlertCheck: (() -> Boolean)? = null,
    private val serverName: String = "rouse-context",
    private val serverVersion: String = "0.1.0",
    private val log: (LogLevel, String) -> Unit = { _, _ -> }
) {

    private var done = CompletableDeferred<Unit>()
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Returns the actual port the HTTP server is listening on.
     * Only valid after [start] has been called. Returns -1 if not started.
     */
    @Volatile
    var port: Int = -1
        private set

    /**
     * Shared secret the bridge injects as `X-Internal-Token` on every
     * plaintext HTTP request forwarded into the local Ktor server. Rotates
     * on every call to [start]; never persisted. See issue #177.
     *
     * Throws if read before [start]. Callers in the same process (the
     * `McpSessionBridge` / `SessionHandler`) must retrieve this after the
     * server has been started.
     */
    @Volatile
    private var _internalToken: String? = null
    val internalToken: String
        get() = _internalToken
            ?: error("McpSession not started; internalToken unavailable")

    /**
     * Starts the embedded HTTP server on the given port.
     * Use port 0 for an OS-assigned ephemeral port.
     *
     * Call [resolvePort] after starting to determine the actual listening port.
     */
    fun start(port: Int = 0) {
        // Rotate the internal token on each start so cross-session replay
        // (e.g. a snooping app caching a leaked token between restarts) is
        // neutralised. See issue #177.
        val token = generateInternalToken()
        _internalToken = token
        // Allow restarts after stop(): reset the done deferred if it was
        // completed by a previous stop().
        if (done.isCompleted) {
            done = CompletableDeferred()
        }
        val server = embeddedServer(CIO, port = port, host = LOOPBACK_HOST) {
            configureMcpRouting(
                registry = registry,
                tokenStore = tokenStore,
                deviceCodeManager = deviceCodeManager,
                authorizationCodeManager = authorizationCodeManager,
                hostname = hostname,
                integration = integration,
                auditListener = auditListener,
                rateLimiter = rateLimiter,
                mcpRateLimiter = mcpRateLimiter,
                securityAlertCheck = securityAlertCheck,
                serverName = serverName,
                serverVersion = serverVersion,
                internalToken = token,
                log = log
            )
        }
        engine = server
        server.start(wait = false)
    }

    /**
     * Resolves the actual port the server is listening on.
     * Must be called after [start]. This is suspend because Ktor CIO's
     * `resolvedConnectors()` is a suspend function.
     */
    suspend fun resolvePort(): Int {
        val resolvedPort = engine?.engine?.resolvedConnectors()?.firstOrNull()?.port ?: -1
        port = resolvedPort
        return resolvedPort
    }

    /**
     * Resolves the interface this server is bound to. Exposed for regression tests
     * guarding the loopback-only bind in [LOOPBACK_HOST]; see issue #127.
     *
     * Must be called after [start]. Returns null if the engine has no connectors.
     */
    internal suspend fun resolveHost(): String? =
        engine?.engine?.resolvedConnectors()?.firstOrNull()?.host

    /**
     * Suspends until [stop] is called.
     */
    suspend fun awaitClose() {
        done.await()
    }

    /**
     * Stops the HTTP server and completes the session.
     */
    fun stop() {
        engine?.stop()
        done.complete(Unit)
    }

    companion object {
        /**
         * Bind to loopback only. The MCP HTTP server receives plaintext bytes from
         * the tunnel bridge (same process, localhost TCP) — no legitimate caller
         * ever reaches it over a network interface. Binding to 0.0.0.0 would expose
         * the OAuth discovery and device-code endpoints to any host on the same
         * LAN. See issue #127.
         */
        private const val LOOPBACK_HOST = "127.0.0.1"

        /**
         * Default per-integration limit for the OAuth initiation endpoints
         * (`/authorize`, `/device/authorize`, and `/register`): five requests
         * every sixty seconds.
         *
         * The bind host is `127.0.0.1` (see #127) so all callers share the
         * loopback address, which makes the sliding window effectively
         * per-device. This is intentional: the threat being mitigated is a
         * hostile same-device app spamming the user's approval UI through
         * these endpoints. The token-exchange endpoint is deliberately
         * excluded — device-code polling legitimately hits it many times.
         * See issue #176.
         */
        const val OAUTH_INIT_MAX_REQUESTS: Int = 5
        const val OAUTH_INIT_WINDOW_MS: Long = 60_000L

        fun defaultOAuthInitRateLimiter(clock: Clock = SystemClock): RateLimiter = RateLimiter(
            maxRequests = OAUTH_INIT_MAX_REQUESTS,
            windowMs = OAUTH_INIT_WINDOW_MS,
            clock = clock
        )
    }
}
