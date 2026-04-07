package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationCodeFlowTest {

    // Valid 43-char base64url code_challenge for tests that don't need a matching verifier
    private val validChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    // RFC 7636 Appendix B test vectors
    private val rfcCodeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
    private val rfcCodeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

    private val defaultRedirectUri = "http://localhost:3000/callback"

    private fun createManager(
        clock: Clock = FakeClock(),
        tokenStore: TokenStore = InMemoryTokenStore()
    ): AuthorizationCodeManager {
        return AuthorizationCodeManager(tokenStore = tokenStore, clock = clock)
    }

    /** Registers a client with the default redirect URI and returns the clientId. */
    private fun AuthorizationCodeManager.registerTestClient(
        clientId: String = "test-client",
        clientName: String = "Test",
        redirectUris: List<String> = listOf(defaultRedirectUri)
    ): String {
        registerClient(clientId, clientName, redirectUris)
        return clientId
    }

    @Test
    fun `createRequest returns request with display code and request id`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = rfcCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "some-state",
            integration = "health"
        )

        assertNotNull(request.requestId)
        assertTrue(request.requestId.isNotEmpty())
        assertNotNull(request.displayCode)
        // Display code format: XXXXXX-XXXXXX (12 chars total for ~61 bits entropy)
        assertTrue(request.displayCode.matches(Regex("[A-Z2-9]{6}-[A-Z2-9]{6}")))
        assertEquals("health", request.integration)
    }

    @Test
    fun `getStatus returns pending for new request`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        val status = manager.getStatus(request.requestId)
        assertTrue(status is AuthorizationRequestStatus.Pending)
    }

    @Test
    fun `approve by display code transitions to approved`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "my-state",
            integration = "health"
        )

        val approved = manager.approve(request.displayCode)
        assertTrue(approved)

        val status = manager.getStatus(request.requestId)
        assertTrue(status is AuthorizationRequestStatus.Approved)
        val approvedStatus = status as AuthorizationRequestStatus.Approved
        assertNotNull(approvedStatus.code)
        assertTrue(approvedStatus.code.isNotEmpty())
        assertEquals("my-state", approvedStatus.state)
    }

    @Test
    fun `deny by display code transitions to denied`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        val denied = manager.deny(request.displayCode)
        assertTrue(denied)

        val status = manager.getStatus(request.requestId)
        assertTrue(status is AuthorizationRequestStatus.Denied)
    }

    @Test
    fun `approve with unknown display code returns false`() {
        val manager = createManager()
        assertFalse(manager.approve("ZZZZ-ZZZZ"))
    }

    @Test
    fun `deny with unknown display code returns false`() {
        val manager = createManager()
        assertFalse(manager.deny("ZZZZ-ZZZZ"))
    }

    @Test
    fun `expired request returns expired status`() {
        val clock = FakeClock()
        val manager = createManager(clock = clock)
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        clock.advanceMinutes(11)

        val status = manager.getStatus(request.requestId)
        assertTrue(status is AuthorizationRequestStatus.Expired)
    }

    @Test
    fun `unknown request id returns expired`() {
        val manager = createManager()
        val status = manager.getStatus("nonexistent-id")
        assertTrue(status is AuthorizationRequestStatus.Expired)
    }

    @Test
    fun `PKCE S256 validation succeeds with correct verifier`() {
        val tokenStore = InMemoryTokenStore()
        val manager = createManager(tokenStore = tokenStore)
        manager.registerTestClient()

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = rfcCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        val token = manager.exchangeCode(status.code, rfcCodeVerifier)
        assertNotNull(token)
        assertTrue(tokenStore.validateToken("health", token!!))
    }

    @Test
    fun `PKCE S256 validation fails with wrong verifier`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = rfcCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        // Valid format (43+ chars, unreserved charset) but wrong verifier
        val wrongVerifier = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val token = manager.exchangeCode(status.code, wrongVerifier)
        assertNull(token)
    }

    @Test
    fun `exchangeCode with invalid code returns null`() {
        val manager = createManager()
        val validVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertNull(manager.exchangeCode("invalid-code", validVerifier))
    }

    @Test
    fun `authorization code can only be exchanged once`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = rfcCodeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        val token1 = manager.exchangeCode(status.code, rfcCodeVerifier)
        assertNotNull(token1)

        // Second exchange attempt should fail
        val token2 = manager.exchangeCode(status.code, rfcCodeVerifier)
        assertNull(token2)
    }

    @Test
    fun `pendingRequests returns only pending requests`() {
        val manager = createManager()
        manager.registerClient("c1", "Client 1", listOf("http://localhost/cb"))
        manager.registerClient("c2", "Client 2", listOf("http://localhost/cb"))
        val req1 = manager.createRequest(
            clientId = "c1",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )
        val req2 = manager.createRequest(
            clientId = "c2",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s2",
            integration = "notif"
        )

        // Approve first request
        manager.approve(req1.displayCode)

        val pending = manager.pendingRequests()
        assertEquals(1, pending.size)
        assertEquals(req2.displayCode, pending[0].displayCode)
        assertEquals("notif", pending[0].integration)
    }

    @Test
    fun `onNewRequest callback is invoked when request is created`() {
        val manager = createManager()
        var callbackCode: String? = null
        var callbackIntegration: String? = null
        manager.onNewRequest = { code, integration ->
            callbackCode = code
            callbackIntegration = integration
        }

        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "s1",
            integration = "health"
        )

        assertEquals(request.displayCode, callbackCode)
        assertEquals("health", callbackIntegration)
    }

    @Test
    fun `onNewRequest is not called when callback is null`() {
        val manager = createManager()
        manager.registerTestClient()
        // Should not throw when callback is null
        manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "s1",
            integration = "health"
        )
    }

    @Test
    fun `pendingRequests excludes expired requests`() {
        val clock = FakeClock()
        val manager = createManager(clock = clock)
        manager.registerClient("c1", "Client 1", listOf("http://localhost/cb"))
        manager.createRequest(
            clientId = "c1",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )

        clock.advanceMinutes(11)

        val pending = manager.pendingRequests()
        assertTrue(pending.isEmpty())
    }

    // --- PKCE format validation tests ---

    @Test
    fun `createRequest rejects code_challenge shorter than 43 chars`() {
        val manager = createManager()
        manager.registerTestClient()
        try {
            manager.createRequest(
                clientId = "test-client",
                codeChallenge = "short",
                codeChallengeMethod = "S256",
                redirectUri = defaultRedirectUri,
                state = "state",
                integration = "health"
            )
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("code_challenge"))
        }
    }

    @Test
    fun `createRequest rejects code_challenge with invalid chars`() {
        val manager = createManager()
        manager.registerTestClient()
        // 43 chars but contains '=' which is not base64url without padding
        val badChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw=cM"
        try {
            manager.createRequest(
                clientId = "test-client",
                codeChallenge = badChallenge,
                codeChallengeMethod = "S256",
                redirectUri = defaultRedirectUri,
                state = "state",
                integration = "health"
            )
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("code_challenge"))
        }
    }

    @Test
    fun `exchangeCode rejects code_verifier shorter than 43 chars`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )
        manager.approve(request.displayCode)
        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        assertNull(manager.exchangeCode(status.code, "short"))
    }

    @Test
    fun `exchangeCode rejects code_verifier with invalid chars`() {
        val manager = createManager()
        manager.registerTestClient()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = validChallenge,
            codeChallengeMethod = "S256",
            redirectUri = defaultRedirectUri,
            state = "state",
            integration = "health"
        )
        manager.approve(request.displayCode)
        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        // 43 chars but contains '@' which is not in the unreserved set
        val badVerifier = "dBjftJeZ4CVP@mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertNull(manager.exchangeCode(status.code, badVerifier))
    }

    // --- redirect_uri validation tests ---

    @Test
    fun `registerClient rejects redirect_uri with non-http scheme`() {
        val manager = createManager()
        try {
            manager.registerClient("c1", "Test", listOf("ftp://evil.com/callback"))
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("redirect_uri"))
        }
    }

    @Test
    fun `registerClient rejects javascript scheme redirect_uri`() {
        val manager = createManager()
        try {
            manager.registerClient("c1", "Test", listOf("javascript:alert(1)"))
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("redirect_uri"))
        }
    }

    @Test
    fun `createRequest rejects unregistered redirect_uri`() {
        val manager = createManager()
        manager.registerClient("c1", "Test", listOf("http://localhost/registered"))
        try {
            manager.createRequest(
                clientId = "c1",
                codeChallenge = validChallenge,
                codeChallengeMethod = "S256",
                redirectUri = "http://localhost/unregistered",
                state = "state",
                integration = "health"
            )
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("redirect_uri"))
        }
    }

    @Test
    fun `createRequest rejects request from unregistered client`() {
        val manager = createManager()
        // Don't register any client
        try {
            manager.createRequest(
                clientId = "unknown-client",
                codeChallenge = validChallenge,
                codeChallengeMethod = "S256",
                redirectUri = "http://localhost/callback",
                state = "state",
                integration = "health"
            )
            assertTrue("Should have thrown", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("redirect_uri"))
        }
    }
}
