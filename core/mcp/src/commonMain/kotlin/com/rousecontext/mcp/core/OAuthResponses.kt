package com.rousecontext.mcp.core

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * RFC 9728 Protected Resource Metadata.
 *
 * Response for `GET /.well-known/oauth-protected-resource/{path...}`.
 */
@Serializable
data class ProtectedResourceMetadata(
    val resource: String,
    @SerialName("authorization_servers")
    val authorizationServers: List<String>
)

/**
 * RFC 6749 §5.1 successful token response.
 *
 * Response for `POST /token`.
 */
@Serializable
data class TokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("token_type")
    val tokenType: String = "Bearer",
    @SerialName("expires_in")
    val expiresIn: Long,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    val scope: String? = null
)

/**
 * RFC 6749 §5.2 error response shared by all OAuth endpoints.
 */
@Serializable
data class OAuthError(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String? = null,
    @SerialName("error_uri")
    val errorUri: String? = null
)

/**
 * RFC 7591 §3.2.1 Dynamic Client Registration response.
 *
 * Response for `POST /register`.
 */
@Serializable
data class ClientRegistrationResponse(
    @SerialName("client_id")
    val clientId: String,
    @SerialName("client_name")
    val clientName: String? = null,
    @SerialName("redirect_uris")
    val redirectUris: List<String>,
    @SerialName("grant_types")
    val grantTypes: List<String>,
    @SerialName("response_types")
    val responseTypes: List<String>,
    @SerialName("token_endpoint_auth_method")
    val tokenEndpointAuthMethod: String = "none"
)

/**
 * RFC 8628 §3.2 Device Authorization Response.
 *
 * Response for `POST /device/authorize`.
 */
@Serializable
data class DeviceAuthorizationResponse(
    @SerialName("device_code")
    val deviceCode: String,
    @SerialName("user_code")
    val userCode: String,
    @SerialName("verification_uri")
    val verificationUri: String,
    @SerialName("verification_uri_complete")
    val verificationUriComplete: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    val interval: Long = 5L
)

/**
 * Response for `GET /authorize/status`.
 *
 * Uses `@JsonClassDiscriminator("status")` to match the existing flat wire format
 * `{"status": "pending|approved|denied|expired", ...}`. Do NOT rename this discriminator;
 * Claude's MCP client and the e2e tests depend on `status` being the tag field.
 */
@OptIn(ExperimentalSerializationApi::class)
@JsonClassDiscriminator("status")
@Serializable
sealed class AuthorizationStatusResponse {
    @Serializable
    @SerialName("pending")
    data object Pending : AuthorizationStatusResponse()

    @Serializable
    @SerialName("approved")
    data class Approved(val code: String, val state: String? = null) : AuthorizationStatusResponse()

    @Serializable
    @SerialName("denied")
    data object Denied : AuthorizationStatusResponse()

    @Serializable
    @SerialName("expired")
    data object Expired : AuthorizationStatusResponse()
}
