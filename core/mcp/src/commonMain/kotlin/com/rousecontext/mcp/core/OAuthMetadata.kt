package com.rousecontext.mcp.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RFC 8414 OAuth Authorization Server Metadata, per-integration.
 *
 * Generated from the device hostname and integration path prefix.
 */
@Serializable
data class OAuthMetadata(
    val issuer: String,
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,
    @SerialName("device_authorization_endpoint")
    val deviceAuthorizationEndpoint: String,
    @SerialName("token_endpoint")
    val tokenEndpoint: String,
    @SerialName("registration_endpoint")
    val registrationEndpoint: String,
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String>,
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String>,
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>
)

/**
 * Builds the OAuth metadata for a given integration path on this device.
 */
fun buildOAuthMetadata(hostname: String, integrationPath: String): OAuthMetadata {
    val baseUrl = "https://$hostname/$integrationPath"
    return OAuthMetadata(
        issuer = baseUrl,
        authorizationEndpoint = "$baseUrl/authorize",
        deviceAuthorizationEndpoint = "$baseUrl/device/authorize",
        tokenEndpoint = "$baseUrl/token",
        registrationEndpoint = "$baseUrl/register",
        grantTypesSupported = listOf(
            "authorization_code",
            "urn:ietf:params:oauth:grant-type:device_code",
            "refresh_token"
        ),
        responseTypesSupported = listOf("code"),
        codeChallengeMethodsSupported = listOf("S256")
    )
}
