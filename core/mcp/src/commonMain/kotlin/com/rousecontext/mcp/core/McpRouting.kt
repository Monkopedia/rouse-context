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
