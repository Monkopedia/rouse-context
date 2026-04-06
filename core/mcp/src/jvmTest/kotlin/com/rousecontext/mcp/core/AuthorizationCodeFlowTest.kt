package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizationCodeFlowTest {

    private fun createManager(
        clock: Clock = FakeClock(),
        tokenStore: TokenStore = InMemoryTokenStore()
    ): AuthorizationCodeManager {
        return AuthorizationCodeManager(tokenStore = tokenStore, clock = clock)
    }

    @Test
    fun `createRequest returns request with display code and request id`() {
        val manager = createManager()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
            state = "some-state",
            integration = "health"
        )

        assertNotNull(request.requestId)
        assertTrue(request.requestId.isNotEmpty())
        assertNotNull(request.displayCode)
        // Display code format: XXXX-XXXX
        assertTrue(request.displayCode.matches(Regex("[A-Z2-9]{4}-[A-Z2-9]{4}")))
        assertEquals("health", request.integration)
    }

    @Test
    fun `getStatus returns pending for new request`() {
        val manager = createManager()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
            state = "state",
            integration = "health"
        )

        val status = manager.getStatus(request.requestId)
        assertTrue(status is AuthorizationRequestStatus.Pending)
    }

    @Test
    fun `approve by display code transitions to approved`() {
        val manager = createManager()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
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
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
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
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = "challenge",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
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
        // RFC 7636 Appendix B test vector
        val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val tokenStore = InMemoryTokenStore()
        val manager = createManager(tokenStore = tokenStore)

        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = codeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        val token = manager.exchangeCode(status.code, codeVerifier)
        assertNotNull(token)
        assertTrue(tokenStore.validateToken("health", token!!))
    }

    @Test
    fun `PKCE S256 validation fails with wrong verifier`() {
        val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val manager = createManager()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = codeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        val token = manager.exchangeCode(status.code, "wrong-verifier")
        assertNull(token)
    }

    @Test
    fun `exchangeCode with invalid code returns null`() {
        val manager = createManager()
        assertNull(manager.exchangeCode("invalid-code", "some-verifier"))
    }

    @Test
    fun `authorization code can only be exchanged once`() {
        val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val codeChallenge = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"

        val manager = createManager()
        val request = manager.createRequest(
            clientId = "test-client",
            codeChallenge = codeChallenge,
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost:3000/callback",
            state = "state",
            integration = "health"
        )

        manager.approve(request.displayCode)

        val status = manager.getStatus(request.requestId) as AuthorizationRequestStatus.Approved
        val token1 = manager.exchangeCode(status.code, codeVerifier)
        assertNotNull(token1)

        // Second exchange attempt should fail
        val token2 = manager.exchangeCode(status.code, codeVerifier)
        assertNull(token2)
    }

    @Test
    fun `pendingRequests returns only pending requests`() {
        val manager = createManager()
        val req1 = manager.createRequest(
            clientId = "c1",
            codeChallenge = "ch1",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )
        val req2 = manager.createRequest(
            clientId = "c2",
            codeChallenge = "ch2",
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
    fun `pendingRequests excludes expired requests`() {
        val clock = FakeClock()
        val manager = createManager(clock = clock)
        manager.createRequest(
            clientId = "c1",
            codeChallenge = "ch1",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )

        clock.advanceMinutes(11)

        val pending = manager.pendingRequests()
        assertTrue(pending.isEmpty())
    }
}
