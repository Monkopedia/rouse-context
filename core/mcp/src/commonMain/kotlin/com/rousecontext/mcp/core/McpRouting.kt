package com.rousecontext.mcp.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private val mcpJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Per-integration server state: the SDK Server and its HTTP transport.
 */
private data class IntegrationServer(
    val server: Server,
    val transport: HttpTransport
)

/**
 * Configures Ktor routing for MCP Streamable HTTP with per-integration OAuth.
 *
 * Each server instance serves a single integration, identified by hostname.
 * Routes are at root level:
 * - `/.well-known/oauth-authorization-server` -- OAuth metadata (no auth)
 * - `/device/authorize` -- Device code flow start (no auth)
 * - `/token` -- Token exchange (no auth, checks device_code)
 * - `/mcp` -- MCP Streamable HTTP (Bearer auth required)
 */
@Suppress("LongMethod", "LongParameterList", "CyclomaticComplexMethod")
fun Application.configureMcpRouting(
    registry: ProviderRegistry,
    tokenStore: TokenStore,
    deviceCodeManager: DeviceCodeManager,
    auditListener: AuditListener? = null,
    authorizationCodeManager: AuthorizationCodeManager = AuthorizationCodeManager(
        tokenStore,
        auditListener = auditListener
    ),
    hostname: String,
    integration: String,
    clock: Clock = SystemClock,
    rateLimiter: RateLimiter? = null,
    mcpRateLimiter: RateLimiter? = null,
    serverName: String = "rouse-context",
    serverVersion: String = "0.1.0"
) {
    install(ContentNegotiation) {
        json(mcpJson)
    }

    // Per-integration MCP servers, created lazily and cached
    val integrationServers = mutableMapOf<String, IntegrationServer>()
    val serversMutex = Mutex()

    // Resolve the public hostname from the request's Host header, falling back
    // to the configured hostname. This allows a single MCP session to serve
    // multiple integration hostnames correctly.
    fun io.ktor.server.routing.RoutingCall.resolveHostname(): String {
        return request.headers["Host"]?.takeIf { it.isNotEmpty() } ?: hostname
    }

    // Resolve the integration name from the Host header.
    // Hostname format: {adjective}-{integration}.{subdomain}.{domain}
    // e.g. "exact-health.abc123.rousecontext.com" -> "health"
    // Falls back to the configured integration parameter.
    fun io.ktor.server.routing.RoutingCall.resolveIntegration(): String {
        val host = request.headers["Host"] ?: return integration
        val firstLabel = host.split(".").firstOrNull() ?: return integration
        val parts = firstLabel.split("-", limit = 2)
        return if (parts.size == 2) parts[1] else integration
    }

    routing {
        // RFC 9728: Protected Resource Metadata.
        // Claude's MCP client discovers OAuth by requesting:
        //   GET /.well-known/oauth-protected-resource/mcp
        get("/.well-known/oauth-protected-resource/{path...}") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val baseUrl = "https://${call.resolveHostname()}"
            call.respond(
                buildJsonObject {
                    put("resource", "$baseUrl/mcp")
                    put(
                        "authorization_servers",
                        kotlinx.serialization.json.JsonArray(
                            listOf(
                                kotlinx.serialization.json.JsonPrimitive(baseUrl)
                            )
                        )
                    )
                }
            )
        }

        // RFC 8414: OAuth authorization server metadata at root.
        get("/.well-known/oauth-authorization-server/{path...}") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val metadata = buildOAuthMetadata(call.resolveHostname())
            call.respond(metadata)
        }

        get("/.well-known/oauth-authorization-server") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val metadata = buildOAuthMetadata(call.resolveHostname())
            call.respond(metadata)
        }

        // RFC 7591 Dynamic Client Registration -- accepts any client and
        // returns a client_id. We don't enforce client authentication for the
        // device code flow, so this is a simple pass-through.
        post("/register") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (rateLimiter != null && !rateLimiter.tryAcquire("$ri/register")) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }

            val body = try {
                val text = kotlinx.coroutines.withTimeout(5000) {
                    call.receiveText()
                }
                mcpJson.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                println(
                    "McpRouting: /register body read failed: " +
                        "${e::class.simpleName}: ${e.message}"
                )
                call.respondText(
                    "{}",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            val clientName = body["client_name"]?.jsonPrimitive?.content ?: "unknown"

            // Validate redirect_uris: only http/https schemes allowed
            val rawUris = (body["redirect_uris"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { it.jsonPrimitive.content }
                ?: emptyList()
            val validUris = rawUris.filter { uri ->
                try {
                    val scheme = java.net.URI(uri).scheme?.lowercase()
                    scheme == "http" || scheme == "https"
                } catch (_: Exception) {
                    false
                }
            }
            if (validUris.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    buildJsonObject {
                        put("error", "invalid_redirect_uri")
                        put(
                            "error_description",
                            "At least one http or https redirect_uri is required"
                        )
                    }
                )
                return@post
            }
            val redirectUris = validUris

            val clientId = java.util.UUID.randomUUID().toString()
            try {
                authorizationCodeManager.registerClient(clientId, clientName, redirectUris)
            } catch (e: IllegalArgumentException) {
                call.respondText(
                    buildJsonObject {
                        put("error", "invalid_redirect_uri")
                        put("error_description", e.message ?: "Invalid redirect_uri")
                    }.toString(),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }
            val response = buildJsonObject {
                put("client_id", clientId)
                put("client_name", clientName)
                put(
                    "redirect_uris",
                    kotlinx.serialization.json.JsonArray(
                        validUris.map { kotlinx.serialization.json.JsonPrimitive(it) }
                    )
                )
                put(
                    "grant_types",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive(
                                "authorization_code"
                            ),
                            kotlinx.serialization.json.JsonPrimitive(
                                "urn:ietf:params:oauth:grant-type:device_code"
                            )
                        )
                    )
                )
                put(
                    "response_types",
                    kotlinx.serialization.json.JsonArray(
                        listOf(
                            kotlinx.serialization.json.JsonPrimitive("code")
                        )
                    )
                )
                put("token_endpoint_auth_method", "none")
            }
            call.respondText(
                response.toString(),
                ContentType.Application.Json,
                HttpStatusCode.Created
            )
        }

        // Device code authorize -- no auth required
        post("/device/authorize") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }

            val response = deviceCodeManager.authorize(ri)
            call.respond(
                buildJsonObject {
                    put("device_code", response.deviceCode)
                    put("user_code", response.userCode)
                    put("interval", response.interval)
                    put(
                        "verification_uri",
                        "https://${call.resolveHostname()}/device"
                    )
                    put("expires_in", 600)
                }
            )
        }

        // Authorization code flow -- returns HTML page with display code
        get("/authorize") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            if (rateLimiter != null && !rateLimiter.tryAcquire("$ri/authorize")) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@get
            }

            val responseType = call.request.queryParameters["response_type"]
            val clientId = call.request.queryParameters["client_id"]
            val codeChallenge = call.request.queryParameters["code_challenge"]
            val codeChallengeMethod = call.request.queryParameters["code_challenge_method"]
            val redirectUri = call.request.queryParameters["redirect_uri"]
            val state = call.request.queryParameters["state"]

            val validRequest = responseType == "code" && codeChallengeMethod == "S256"
            val allParamsPresent = clientId != null && codeChallenge != null &&
                redirectUri != null && state != null
            if (!validRequest || !allParamsPresent) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            // Validate redirect_uri scheme
            val uriScheme = try {
                java.net.URI(redirectUri!!).scheme?.lowercase()
            } catch (_: Exception) {
                null
            }
            if (uriScheme != "http" && uriScheme != "https") {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            // Non-null guaranteed by allParamsPresent check above
            val request = try {
                authorizationCodeManager.createRequest(
                    clientId = clientId!!,
                    codeChallenge = codeChallenge!!,
                    codeChallengeMethod = codeChallengeMethod!!,
                    redirectUri = redirectUri,
                    state = state!!,
                    integration = ri
                )
            } catch (_: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val html = buildAuthorizePage(
                displayCode = request.displayCode,
                requestId = request.requestId,
                redirectUri = redirectUri,
                hostname = call.resolveHostname(),
                integration = ri
            )
            call.response.headers.append("X-Frame-Options", "DENY")
            call.response.headers.append(
                "Content-Security-Policy",
                "frame-ancestors 'none'; default-src 'self'; " +
                    "script-src 'unsafe-inline'; " +
                    "style-src 'unsafe-inline'; " +
                    "img-src 'self' data:"
            )
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append(
                "Strict-Transport-Security",
                "max-age=31536000"
            )
            call.respondText(html, ContentType.Text.Html)
        }

        // Authorization status polling -- JSON endpoint for JS on the HTML page
        get("/authorize/status") {
            val requestId = call.request.queryParameters["request_id"]
            if (requestId == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }

            val status = authorizationCodeManager.getStatus(requestId)
            val json = when (status) {
                is AuthorizationRequestStatus.Pending ->
                    buildJsonObject { put("status", "pending") }
                is AuthorizationRequestStatus.Approved ->
                    buildJsonObject {
                        put("status", "approved")
                        put("code", status.code)
                        put("state", status.state)
                    }
                is AuthorizationRequestStatus.Denied ->
                    buildJsonObject { put("status", "denied") }
                is AuthorizationRequestStatus.Expired ->
                    buildJsonObject { put("status", "expired") }
            }
            call.respond(json)
        }

        // Token exchange -- handles both authorization_code and device_code
        post("/token") {
            val ri = call.resolveIntegration()
            if (registry.providerForPath(ri) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@post
            }
            if (rateLimiter != null && !rateLimiter.tryAcquire("$ri/token")) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }

            val params = parseTokenRequestParams(call)
            if (params == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            val grantType = params["grant_type"]

            when (grantType) {
                "authorization_code" -> {
                    val code = params["code"]
                    val codeVerifier = params["code_verifier"]
                    if (code == null || codeVerifier == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val pair = authorizationCodeManager.exchangeCode(code, codeVerifier)
                    if (pair != null) {
                        call.respond(
                            buildJsonObject {
                                put("access_token", pair.accessToken)
                                put("token_type", "Bearer")
                                put("expires_in", pair.expiresIn)
                                put("refresh_token", pair.refreshToken)
                            }
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject { put("error", "invalid_grant") }
                        )
                    }
                }
                "urn:ietf:params:oauth:grant-type:device_code" -> {
                    val deviceCode = params["device_code"]
                    if (deviceCode == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    respondToDeviceCodePoll(call, deviceCodeManager, deviceCode)
                }
                "refresh_token" -> {
                    val refreshTokenValue = params["refresh_token"]
                    if (refreshTokenValue == null) {
                        call.respond(HttpStatusCode.BadRequest)
                        return@post
                    }
                    val pair = tokenStore.refreshToken(
                        ri,
                        refreshTokenValue
                    )
                    if (pair != null) {
                        call.respond(
                            buildJsonObject {
                                put("access_token", pair.accessToken)
                                put("token_type", "Bearer")
                                put("expires_in", pair.expiresIn)
                                put("refresh_token", pair.refreshToken)
                            }
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            buildJsonObject { put("error", "invalid_grant") }
                        )
                    }
                }
                else -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        buildJsonObject { put("error", "unsupported_grant_type") }
                    )
                }
            }
        }

        // MCP Streamable HTTP -- Bearer auth required
        post("/mcp") {
            val ri = call.resolveIntegration()
            val provider = registry.providerForPath(ri)
                ?: run {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
            if (mcpRateLimiter != null &&
                !mcpRateLimiter.tryAcquire("$ri/mcp")
            ) {
                call.respond(HttpStatusCode.TooManyRequests)
                return@post
            }

            // Auth check
            val authHeader = call.request.headers["Authorization"]
            val token = authHeader?.removePrefix("Bearer ")?.takeIf {
                authHeader.startsWith("Bearer ")
            }

            if (token == null || !tokenStore.validateToken(ri, token)) {
                call.response.headers.append(
                    "WWW-Authenticate",
                    "Bearer resource_metadata=\"https://${call.resolveHostname()}" +
                        "/.well-known/oauth-protected-resource\""
                )
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            // Get or create MCP server + transport for this integration
            val integrationServer = serversMutex.withLock {
                integrationServers.getOrPut(ri) {
                    createIntegrationServer(provider, serverName, serverVersion)
                }
            }

            // Parse and dispatch JSON-RPC request through SDK transport
            val requestBody = try {
                kotlinx.coroutines.withTimeout(MCP_REQUEST_TIMEOUT_MS) {
                    call.receiveText()
                }
            } catch (e: Exception) {
                println(
                    "McpRouting: /mcp body read failed: " +
                        "${e::class.simpleName}: ${e.message}"
                )
                call.respondText(
                    mcpJson.encodeToString(
                        JsonObject.serializer(),
                        jsonRpcError(JsonNull, -32700, "Request body read timeout")
                    ),
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest
                )
                return@post
            }

            // JSON-RPC notifications have no "id" field and expect no response
            val parsed = try {
                mcpJson.parseToJsonElement(requestBody).jsonObject
            } catch (_: Exception) {
                null
            }
            val isNotification = parsed != null && !parsed.containsKey("id")

            if (isNotification) {
                // Fire-and-forget: dispatch to SDK but return 202 immediately
                dispatchNotification(integrationServer.transport, requestBody)
                call.respond(HttpStatusCode.Accepted)
            } else {
                val startMs = clock.currentTimeMillis()
                val responseJson = dispatchJsonRpc(integrationServer.transport, requestBody)

                if (auditListener != null && parsed != null) {
                    emitAuditEvent(
                        auditListener,
                        parsed,
                        responseJson,
                        ri,
                        startMs,
                        clock
                    )
                }

                call.respondText(responseJson, ContentType.Application.Json)
            }
        }
    }
}

private suspend fun respondToDeviceCodePoll(
    call: io.ktor.server.routing.RoutingCall,
    deviceCodeManager: DeviceCodeManager,
    deviceCode: String
) {
    val result = deviceCodeManager.poll(deviceCode)
    when (result.status) {
        DeviceCodeStatus.APPROVED -> {
            val pair = result.tokenPair!!
            call.respond(
                buildJsonObject {
                    put("access_token", pair.accessToken)
                    put("token_type", "Bearer")
                    put("expires_in", pair.expiresIn)
                    put("refresh_token", pair.refreshToken)
                }
            )
        }
        DeviceCodeStatus.AUTHORIZATION_PENDING -> {
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "authorization_pending") }
            )
        }
        DeviceCodeStatus.ACCESS_DENIED -> {
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "access_denied") }
            )
        }
        DeviceCodeStatus.EXPIRED_TOKEN -> {
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "expired_token") }
            )
        }
        DeviceCodeStatus.INVALID_CODE -> {
            call.respond(
                HttpStatusCode.BadRequest,
                buildJsonObject { put("error", "invalid_grant") }
            )
        }
    }
}

private suspend fun createIntegrationServer(
    provider: McpServerProvider,
    serverName: String,
    serverVersion: String
): IntegrationServer {
    val server = Server(
        Implementation(name = serverName, version = serverVersion),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = false),
                resources = ServerCapabilities.Resources(
                    subscribe = false,
                    listChanged = false
                )
            )
        )
    )
    provider.register(server)

    val transport = HttpTransport()
    server.createSession(transport)

    return IntegrationServer(server, transport)
}

/**
 * Dispatches a raw JSON-RPC request string through the SDK Server via HttpTransport.
 * Returns the JSON-RPC response string.
 */
internal suspend fun dispatchNotification(transport: HttpTransport, requestBody: String) {
    val message = try {
        mcpJson.decodeFromString(JSONRPCMessage.serializer(), requestBody)
    } catch (_: Exception) {
        return
    }
    transport.handleNotification(message)
}

internal suspend fun dispatchJsonRpc(transport: HttpTransport, requestBody: String): String {
    val message = try {
        mcpJson.decodeFromString(JSONRPCMessage.serializer(), requestBody)
    } catch (_: Exception) {
        return mcpJson.encodeToString(
            JsonObject.serializer(),
            jsonRpcError(JsonNull, -32700, "Parse error")
        )
    }

    return try {
        val response = transport.handleRequest(message)
        mcpJson.encodeToString(JSONRPCMessage.serializer(), response)
    } catch (e: Exception) {
        mcpJson.encodeToString(
            JsonObject.serializer(),
            jsonRpcError(JsonNull, -32603, e.message ?: "Internal error")
        )
    }
}

/**
 * Parses token request parameters from either form-encoded or JSON body.
 * Returns a simple map of parameter names to values, or null on parse failure.
 */
private suspend fun parseTokenRequestParams(
    call: io.ktor.server.routing.RoutingCall
): Map<String, String?>? {
    return try {
        if (call.request.contentType().match(ContentType.Application.FormUrlEncoded)) {
            val params = call.receiveParameters()
            params.names().associateWith { params[it] }
        } else {
            val body = mcpJson.parseToJsonElement(call.receiveText()).jsonObject
            body.mapValues { (_, v) ->
                if (v is JsonNull) null else v.jsonPrimitive.content
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Builds the HTML page for the authorization code flow.
 * Shows the display code and polls for approval status via JavaScript.
 */
@Suppress("LongMethod")
internal fun buildAuthorizePage(
    displayCode: String,
    requestId: String,
    redirectUri: String,
    hostname: String,
    integration: String
): String {
    val statusUrl = "https://$hostname/authorize/status?request_id=$requestId"
    return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>Rouse Context - Authorize</title>
            <style>
                * { margin: 0; padding: 0; box-sizing: border-box; }
                body {
                    font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto,
                                 sans-serif;
                    background: #f5f5f5;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    min-height: 100vh;
                    color: #333;
                }
                .card {
                    background: white;
                    border-radius: 12px;
                    padding: 48px;
                    box-shadow: 0 2px 12px rgba(0,0,0,0.1);
                    text-align: center;
                    max-width: 520px;
                    width: 90%;
                }
                .logo { font-size: 24px; font-weight: 700; margin-bottom: 24px; }
                .code {
                    font-size: 36px;
                    font-weight: 700;
                    font-family: 'SF Mono', 'Fira Code', monospace;
                    letter-spacing: 4px;
                    margin: 24px 0;
                    padding: 16px;
                    background: #f0f0f0;
                    border-radius: 8px;
                }
                .instruction {
                    color: #666;
                    margin-bottom: 24px;
                    line-height: 1.5;
                }
                .spinner {
                    width: 32px; height: 32px;
                    border: 3px solid #e0e0e0;
                    border-top-color: #333;
                    border-radius: 50%;
                    animation: spin 0.8s linear infinite;
                    margin: 16px auto;
                }
                @keyframes spin { to { transform: rotate(360deg); } }
                #status { margin-top: 16px; color: #666; }
                .error { color: #c00; }
                .success { color: #080; }
            </style>
        </head>
        <body>
            <div class="card">
                <img src="data:image/png;base64,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" width="64" height="64" alt="Rouse Context" style="border-radius: 16px; margin-bottom: 12px; display: block; margin-left: auto; margin-right: auto;" />
                <div class="logo">Rouse Context</div>
                <p class="instruction">Approve this request on your device</p>
                <div class="code">$displayCode</div>
                <p class="instruction">
                    Check your phone for a notification with this code
                </p>
                <div class="spinner" id="spinner"></div>
                <div id="status">Waiting for approval...</div>
            </div>
            <script>
                var statusUrl = '${htmlEscapeForJs(statusUrl)}';
                var redirectBase = '${htmlEscapeForJs(redirectUri)}';
                var poll = setInterval(async function() {
                    try {
                        var resp = await fetch(statusUrl);
                        var data = await resp.json();
                        if (data.status === 'approved') {
                            clearInterval(poll);
                            document.getElementById('spinner').style.display = 'none';
                            document.getElementById('status').className = 'success';
                            document.getElementById('status').textContent = 'Approved! Redirecting...';
                            var redirect = new URL(redirectBase);
                            redirect.searchParams.set('code', data.code);
                            redirect.searchParams.set('state', data.state);
                            window.location.href = redirect.toString();
                        } else if (data.status === 'denied') {
                            clearInterval(poll);
                            document.getElementById('spinner').style.display = 'none';
                            document.getElementById('status').className = 'error';
                            document.getElementById('status').textContent = 'Request denied';
                        } else if (data.status === 'expired') {
                            clearInterval(poll);
                            document.getElementById('spinner').style.display = 'none';
                            document.getElementById('status').className = 'error';
                            document.getElementById('status').textContent = 'Request expired';
                        }
                    } catch (e) {
                        // Ignore transient fetch errors
                    }
                }, 1000);
            </script>
        </body>
        </html>
    """.trimIndent()
}

/**
 * Emits an audit event for tools/call requests.
 * Silently ignores non-tool-call methods and parse failures.
 */
@Suppress("TooGenericExceptionCaught")
private fun emitAuditEvent(
    auditListener: AuditListener,
    request: JsonObject,
    responseJson: String,
    integration: String,
    startMs: Long,
    clock: Clock
) {
    val method = try {
        request["method"]?.jsonPrimitive?.content
    } catch (_: Exception) {
        null
    }
    if (method != "tools/call") return

    try {
        val params = request["params"]?.jsonObject ?: return
        val toolName = params["name"]?.jsonPrimitive?.content ?: return
        val arguments = params["arguments"]?.jsonObject
            ?.mapValues { (_, v) -> v }
            ?: emptyMap()

        val endMs = clock.currentTimeMillis()

        // Parse response to determine success and extract result
        val responseObj = mcpJson.parseToJsonElement(responseJson).jsonObject
        val error = responseObj["error"]
        val isError = error != null && error !is JsonNull
        val resultObj = responseObj["result"]?.jsonObject

        val result = if (isError) {
            val errorMsg = error?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: "Unknown error"
            CallToolResult(
                content = listOf(TextContent(errorMsg)),
                isError = true
            )
        } else {
            val contentArray = resultObj?.get("content")?.jsonArray
            val textContent = contentArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val text = obj["text"]?.jsonPrimitive?.content
                text?.let { TextContent(it) }
            } ?: emptyList()
            val resultIsError = resultObj?.get("isError")?.jsonPrimitive?.content == "true"
            CallToolResult(content = textContent, isError = resultIsError)
        }

        auditListener.onToolCall(
            ToolCallEvent(
                sessionId = integration,
                providerId = integration,
                timestamp = startMs,
                toolName = toolName,
                arguments = arguments,
                result = result,
                durationMs = endMs - startMs
            )
        )
    } catch (e: Exception) {
        // Audit is best-effort; never fail the request due to audit errors
        println("Audit: failed to emit event: ${e.message}")
    }
}

/**
 * HTML-escapes a string for safe embedding inside a JavaScript string literal
 * within an HTML `<script>` tag. Escapes &, <, >, ", ', /, and backslash.
 */
internal fun htmlEscapeForJs(value: String): String {
    val sb = StringBuilder(value.length)
    for (ch in value) {
        when (ch) {
            '&' -> sb.append("\\x26")
            '<' -> sb.append("\\x3c")
            '>' -> sb.append("\\x3e")
            '"' -> sb.append("\\x22")
            '\'' -> sb.append("\\x27")
            '\\' -> sb.append("\\\\")
            '/' -> sb.append("\\x2f")
            else -> sb.append(ch)
        }
    }
    return sb.toString()
}

/** Timeout for reading the MCP request body and dispatching it (30 seconds). */
private const val MCP_REQUEST_TIMEOUT_MS = 30_000L

private fun jsonRpcError(
    id: kotlinx.serialization.json.JsonElement,
    code: Int,
    message: String
): JsonObject {
    return buildJsonObject {
        put("jsonrpc", "2.0")
        put(
            "error",
            buildJsonObject {
                put("code", code)
                put("message", message)
            }
        )
        put("id", id)
    }
}
