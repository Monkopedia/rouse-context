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
import io.ktor.server.routing.route
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
 * Routes:
 * - `/{integration}/.well-known/oauth-authorization-server` -- OAuth metadata (no auth)
 * - `/{integration}/device/authorize` -- Device code flow start (no auth)
 * - `/{integration}/token` -- Token exchange (no auth, checks device_code)
 * - `/{integration}/mcp` -- MCP Streamable HTTP (Bearer auth required)
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

    routing {
        // RFC 9728: Protected Resource Metadata for path-based MCP endpoints.
        // Claude's MCP client discovers OAuth by requesting:
        //   GET /.well-known/oauth-protected-resource/{integration}/mcp
        get("/.well-known/oauth-protected-resource/{path...}") {
            val fullPath = call.parameters.getAll("path")?.joinToString("/") ?: ""
            // Extract integration name from path (e.g. "test/mcp" -> "test")
            val integration = fullPath.split("/").firstOrNull() ?: ""
            if (integration.isBlank() || registry.providerForPath(integration) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val baseUrl = "https://$hostname/$integration"
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

        // RFC 8414 path-based discovery: given issuer https://host/test,
        // clients look for metadata at /.well-known/oauth-authorization-server/test
        get("/.well-known/oauth-authorization-server/{path...}") {
            val integration = call.parameters.getAll("path")?.firstOrNull() ?: ""
            if (integration.isBlank() || registry.providerForPath(integration) == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val metadata = buildOAuthMetadata(hostname, integration)
            call.respond(metadata)
        }

        // Fallback: root-level OAuth authorization server metadata.
        get("/.well-known/oauth-authorization-server") {
            val integration = registry.enabledPaths().firstOrNull()
            if (integration == null) {
                call.respond(HttpStatusCode.NotFound)
                return@get
            }
            val metadata = buildOAuthMetadata(hostname, integration)
            call.respond(metadata)
        }

        route("/{integration}") {
            // OAuth metadata -- no auth required
            get("/.well-known/oauth-authorization-server") {
                val integration = call.parameters["integration"] ?: return@get
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                val metadata = buildOAuthMetadata(hostname, integration)
                call.respond(metadata)
            }

            // RFC 7591 Dynamic Client Registration -- accepts any client and
            // returns a client_id. We don't enforce client authentication for the
            // device code flow, so this is a simple pass-through.
            post("/register") {
                val integration = call.parameters["integration"] ?: return@post
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                if (rateLimiter != null && !rateLimiter.tryAcquire("$integration/register")) {
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
                val integration = call.parameters["integration"] ?: return@post
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }

                val response = deviceCodeManager.authorize(integration)
                call.respond(
                    buildJsonObject {
                        put("device_code", response.deviceCode)
                        put("user_code", response.userCode)
                        put("interval", response.interval)
                        put(
                            "verification_uri",
                            "https://$hostname/$integration/device"
                        )
                        put("expires_in", 600)
                    }
                )
            }

            // Authorization code flow -- returns HTML page with display code
            get("/authorize") {
                val integration = call.parameters["integration"] ?: return@get
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                if (rateLimiter != null && !rateLimiter.tryAcquire("$integration/authorize")) {
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
                        integration = integration
                    )
                } catch (_: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@get
                }

                val html = buildAuthorizePage(
                    displayCode = request.displayCode,
                    requestId = request.requestId,
                    redirectUri = redirectUri,
                    hostname = hostname,
                    integration = integration
                )
                call.response.headers.append("X-Frame-Options", "DENY")
                call.response.headers.append(
                    "Content-Security-Policy",
                    "frame-ancestors 'none'; default-src 'self'; script-src 'unsafe-inline'"
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
                val integration = call.parameters["integration"] ?: return@post
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                if (rateLimiter != null && !rateLimiter.tryAcquire("$integration/token")) {
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
                            integration,
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
                val integration = call.parameters["integration"] ?: return@post
                if (!INTEGRATION_NAME_REGEX.matches(integration)) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }
                val provider = registry.providerForPath(integration)
                if (provider == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                if (mcpRateLimiter != null &&
                    !mcpRateLimiter.tryAcquire("$integration/mcp")
                ) {
                    call.respond(HttpStatusCode.TooManyRequests)
                    return@post
                }

                // Auth check
                val authHeader = call.request.headers["Authorization"]
                val token = authHeader?.removePrefix("Bearer ")?.takeIf {
                    authHeader.startsWith("Bearer ")
                }

                if (token == null || !tokenStore.validateToken(integration, token)) {
                    call.response.headers.append(
                        "WWW-Authenticate",
                        "Bearer resource_metadata=\"https://$hostname/$integration" +
                            "/.well-known/oauth-authorization-server\""
                    )
                    call.respond(HttpStatusCode.Unauthorized)
                    return@post
                }

                // Get or create MCP server + transport for this integration
                val integrationServer = serversMutex.withLock {
                    integrationServers.getOrPut(integration) {
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
                            integration,
                            startMs,
                            clock
                        )
                    }

                    call.respondText(responseJson, ContentType.Application.Json)
                }
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
private fun buildAuthorizePage(
    displayCode: String,
    requestId: String,
    redirectUri: String,
    hostname: String,
    integration: String
): String {
    val statusUrl = "https://$hostname/$integration/authorize/status?request_id=$requestId"
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
                    max-width: 420px;
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
                }, 3000);
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

/** Valid integration name pattern: alphanumeric, hyphens, underscores, 1-64 chars. */
private val INTEGRATION_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")

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
