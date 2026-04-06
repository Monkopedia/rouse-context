package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {

    // -- AuthorizationCodeManager cleanup --

    @Test
    fun `createRequest cleans up expired requests`() {
        val clock = FakeClock()
        val manager = AuthorizationCodeManager(clock = clock)

        // Create a request, then expire it
        manager.createRequest(
            clientId = "c1",
            codeChallenge = "ch1",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )

        clock.advanceMinutes(11)

        // Creating a new request should clean up the expired one
        manager.createRequest(
            clientId = "c2",
            codeChallenge = "ch2",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s2",
            integration = "health"
        )

        // Only the new request should be pending
        val pending = manager.pendingRequests()
        assertEquals(1, pending.size)
        assertEquals("c2", pending[0].clientId)
    }

    @Test
    fun `getStatus cleans up expired requests`() {
        val clock = FakeClock()
        val manager = AuthorizationCodeManager(clock = clock)

        val req1 = manager.createRequest(
            clientId = "c1",
            codeChallenge = "ch1",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s1",
            integration = "health"
        )
        manager.createRequest(
            clientId = "c2",
            codeChallenge = "ch2",
            codeChallengeMethod = "S256",
            redirectUri = "http://localhost/cb",
            state = "s2",
            integration = "health"
        )

        clock.advanceMinutes(11)

        // Querying status should trigger cleanup of all expired
        manager.getStatus(req1.requestId)

        val pending = manager.pendingRequests()
        assertTrue(pending.isEmpty())
    }

    // -- DeviceCodeManager cleanup --

    @Test
    fun `authorize cleans up expired device codes`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)

        manager.authorize("health")

        clock.advanceMinutes(11)

        // Creating a new code should clean up expired ones
        manager.authorize("health")

        val pending = manager.pendingCodes()
        assertEquals(1, pending.size)
    }

    @Test
    fun `authorize cleans up other expired device codes beyond polled one`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)

        // Create two codes, expire them both
        manager.authorize("health")
        manager.authorize("health")

        clock.advanceMinutes(11)

        // Creating a new code triggers cleanup of both expired ones
        manager.authorize("health")

        // Only the new (non-expired) code should remain
        val pending = manager.pendingCodes()
        assertEquals(1, pending.size)
    }
}
