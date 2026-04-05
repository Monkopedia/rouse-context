package com.rousecontext.mcp.core

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.JSONRPCMessage
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
@Suppress("LongMethod")
fun Application.configureMcpRouting(
    registry: ProviderRegistry,
    tokenStore: TokenStore,
    deviceCodeManager: DeviceCodeManager,
    hostname: String,
    @Suppress("UNUSED_PARAMETER")
    auditListener: AuditListener? = null,
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
        route("/{integration}") {
            // OAuth metadata -- no auth required
            get("/.well-known/oauth-authorization-server") {
                val integration = call.parameters["integration"] ?: return@get
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }

                val metadata = buildOAuthMetadata(hostname, integration)
                call.respond(metadata)
            }

            // Device code authorize -- no auth required
            post("/device/authorize") {
                val integration = call.parameters["integration"] ?: return@post
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

            // Token exchange -- no auth required (validates device_code)
            post("/token") {
                val integration = call.parameters["integration"] ?: return@post
                if (registry.providerForPath(integration) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }

                val body = try {
                    mcpJson.parseToJsonElement(call.receiveText()).jsonObject
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

                val deviceCode = body["device_code"]?.jsonPrimitive?.content
                if (deviceCode == null) {
                    call.respond(HttpStatusCode.BadRequest)
                    return@post
                }

                respondToDeviceCodePoll(call, deviceCodeManager, deviceCode)
            }

            // MCP Streamable HTTP -- Bearer auth required
            post("/mcp") {
                val integration = call.parameters["integration"] ?: return@post
                val provider = registry.providerForPath(integration)
                if (provider == null) {
                    call.respond(HttpStatusCode.NotFound)
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
                val requestBody = call.receiveText()
                val responseJson = dispatchJsonRpc(integrationServer.transport, requestBody)
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
            call.respond(
                buildJsonObject {
                    put("access_token", result.accessToken!!)
                    put("token_type", "Bearer")
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
    server.connect(transport)

    return IntegrationServer(server, transport)
}

/**
 * Dispatches a raw JSON-RPC request string through the SDK Server via HttpTransport.
 * Returns the JSON-RPC response string.
 */
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
