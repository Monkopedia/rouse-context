package com.rousecontext.mcp.core

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snapshot tests pinning the wire format of each OAuth response data class.
 *
 * These responses are consumed by Claude's MCP client and our own e2e tests.
 * Any change that alters field names, order, or presence MUST be evaluated
 * against wire compatibility before being shipped.
 */
class OAuthResponsesTest {

    // Matches the mcpJson instance in McpRouting.kt.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Test
    fun `ProtectedResourceMetadata matches RFC 9728 shape`() {
        val response = ProtectedResourceMetadata(
            resource = "https://brave-health.abc123.rousecontext.com/mcp",
            authorizationServers = listOf(
                "https://brave-health.abc123.rousecontext.com"
            )
        )

        val expected = """{"resource":"https://brave-health.abc123.rousecontext.com/mcp",""" +
            """"authorization_servers":["https://brave-health.abc123.rousecontext.com"]}"""
        assertEquals(
            expected,
            json.encodeToString(ProtectedResourceMetadata.serializer(), response)
        )
    }

    @Test
    fun `TokenResponse with refresh token serializes full RFC 6749 shape`() {
        val response = TokenResponse(
            accessToken = "access-123",
            expiresIn = 3600L,
            refreshToken = "refresh-456"
        )

        val expected =
            """{"access_token":"access-123","token_type":"Bearer","expires_in":3600,""" +
                """"refresh_token":"refresh-456"}"""
        assertEquals(expected, json.encodeToString(TokenResponse.serializer(), response))
    }

    @Test
    fun `TokenResponse omits null refresh token`() {
        val response = TokenResponse(accessToken = "access-123", expiresIn = 3600L)

        val actual = json.encodeToString(TokenResponse.serializer(), response)
        assertEquals(
            """{"access_token":"access-123","token_type":"Bearer","expires_in":3600}""",
            actual
        )
    }

    @Test
    fun `OAuthError minimal form is just error field`() {
        val response = OAuthError(error = "invalid_grant")

        assertEquals(
            """{"error":"invalid_grant"}""",
            json.encodeToString(OAuthError.serializer(), response)
        )
    }

    @Test
    fun `OAuthError with description`() {
        val response = OAuthError(
            error = "invalid_redirect_uri",
            errorDescription = "At least one http or https redirect_uri is required"
        )
        val expected = """{"error":"invalid_redirect_uri",""" +
            """"error_description":"At least one http or https redirect_uri is required"}"""
        assertEquals(expected, json.encodeToString(OAuthError.serializer(), response))
    }

    @Test
    fun `ClientRegistrationResponse matches RFC 7591 shape`() {
        val response = ClientRegistrationResponse(
            clientId = "client-123",
            clientName = "Claude",
            redirectUris = listOf("https://claude.ai/cb"),
            grantTypes = listOf(
                "authorization_code",
                "urn:ietf:params:oauth:grant-type:device_code"
            ),
            responseTypes = listOf("code")
        )

        val expected = """{"client_id":"client-123","client_name":"Claude",""" +
            """"redirect_uris":["https://claude.ai/cb"],""" +
            """"grant_types":["authorization_code",""" +
            """"urn:ietf:params:oauth:grant-type:device_code"],""" +
            """"response_types":["code"],"token_endpoint_auth_method":"none"}"""
        assertEquals(
            expected,
            json.encodeToString(ClientRegistrationResponse.serializer(), response)
        )
    }

    @Test
    fun `DeviceAuthorizationResponse matches RFC 8628 shape`() {
        val response = DeviceAuthorizationResponse(
            deviceCode = "dev-abc",
            userCode = "ABCDEF-GHIJKL",
            verificationUri = "https://host.example/device",
            expiresIn = 600L,
            interval = 5L
        )
        val expected = """{"device_code":"dev-abc","user_code":"ABCDEF-GHIJKL",""" +
            """"verification_uri":"https://host.example/device",""" +
            """"expires_in":600,"interval":5}"""
        assertEquals(
            expected,
            json.encodeToString(DeviceAuthorizationResponse.serializer(), response)
        )
    }

    @Test
    fun `AuthorizationStatusResponse Pending serializes to status field only`() {
        val status: AuthorizationStatusResponse = AuthorizationStatusResponse.Pending
        assertEquals(
            """{"status":"pending"}""",
            json.encodeToString(AuthorizationStatusResponse.serializer(), status)
        )
    }

    @Test
    fun `AuthorizationStatusResponse Approved serializes with code and state`() {
        val status: AuthorizationStatusResponse =
            AuthorizationStatusResponse.Approved(code = "code-xyz", state = "state-abc")
        assertEquals(
            """{"status":"approved","code":"code-xyz","state":"state-abc"}""",
            json.encodeToString(AuthorizationStatusResponse.serializer(), status)
        )
    }

    @Test
    fun `AuthorizationStatusResponse Approved omits null state`() {
        val status: AuthorizationStatusResponse =
            AuthorizationStatusResponse.Approved(code = "code-xyz")
        assertEquals(
            """{"status":"approved","code":"code-xyz"}""",
            json.encodeToString(AuthorizationStatusResponse.serializer(), status)
        )
    }

    @Test
    fun `AuthorizationStatusResponse Denied serializes to status field only`() {
        val status: AuthorizationStatusResponse = AuthorizationStatusResponse.Denied
        assertEquals(
            """{"status":"denied"}""",
            json.encodeToString(AuthorizationStatusResponse.serializer(), status)
        )
    }

    @Test
    fun `AuthorizationStatusResponse Expired serializes to status field only`() {
        val status: AuthorizationStatusResponse = AuthorizationStatusResponse.Expired
        assertEquals(
            """{"status":"expired"}""",
            json.encodeToString(AuthorizationStatusResponse.serializer(), status)
        )
    }

    @Test
    fun `AuthorizationStatusResponse roundtrips each variant`() {
        val variants = listOf(
            AuthorizationStatusResponse.Pending,
            AuthorizationStatusResponse.Approved(code = "c", state = "s"),
            AuthorizationStatusResponse.Approved(code = "c"),
            AuthorizationStatusResponse.Denied,
            AuthorizationStatusResponse.Expired
        )
        for (v in variants) {
            val encoded = json.encodeToString(AuthorizationStatusResponse.serializer(), v)
            val decoded = json.decodeFromString(AuthorizationStatusResponse.serializer(), encoded)
            assertEquals(v, decoded)
        }
    }
}
