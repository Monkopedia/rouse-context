package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceCodeFlowTest {

    @Test
    fun `authorize returns device code and user code`() {
        val manager = DeviceCodeManager()
        val response = manager.authorize("health")

        assertNotNull(response.deviceCode)
        assertNotNull(response.userCode)
        assertTrue(response.deviceCode.isNotEmpty())
        // User code format: XXXXXX-XXXXXX (12 alphanumeric + dash, ~61 bits entropy)
        assertTrue(response.userCode.matches(Regex("[A-Z2-9]{6}-[A-Z2-9]{6}")))
        assertEquals(5, response.interval)
    }

    @Test
    fun `poll before approval returns authorization pending`() {
        val manager = DeviceCodeManager()
        val auth = manager.authorize("health")

        val result = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, result.status)
    }

    @Test
    fun `poll after approval returns access token`() {
        val tokenStore = InMemoryTokenStore()
        val manager = DeviceCodeManager(tokenStore = tokenStore)
        val auth = manager.authorize("health")

        manager.approve(auth.userCode)

        val result = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.APPROVED, result.status)
        assertNotNull(result.tokenPair)
        assertTrue(tokenStore.validateToken("health", result.tokenPair!!.accessToken))
    }

    @Test
    fun `poll after denial returns access denied`() {
        val manager = DeviceCodeManager()
        val auth = manager.authorize("health")

        manager.deny(auth.userCode)

        val result = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.ACCESS_DENIED, result.status)
    }

    @Test
    fun `poll after expiry returns expired token`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)
        val auth = manager.authorize("health")

        // Advance past 10-minute TTL
        clock.advanceMinutes(11)

        val result = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.EXPIRED_TOKEN, result.status)
    }

    @Test
    fun `multiple concurrent codes tracked independently`() {
        val manager = DeviceCodeManager()
        val auth1 = manager.authorize("health")
        val auth2 = manager.authorize("health")

        assertNotEquals(auth1.deviceCode, auth2.deviceCode)
        assertNotEquals(auth1.userCode, auth2.userCode)

        // Approve first, deny second
        manager.approve(auth1.userCode)
        manager.deny(auth2.userCode)

        val result1 = manager.poll(auth1.deviceCode)
        val result2 = manager.poll(auth2.deviceCode)

        assertEquals(DeviceCodeStatus.APPROVED, result1.status)
        assertEquals(DeviceCodeStatus.ACCESS_DENIED, result2.status)
    }

    @Test
    fun `poll with invalid device code returns error`() {
        val manager = DeviceCodeManager()

        val result = manager.poll("nonexistent-device-code")
        assertEquals(DeviceCodeStatus.INVALID_CODE, result.status)
    }

    @Test
    fun `device codes across different integrations are independent`() {
        val manager = DeviceCodeManager()
        val healthAuth = manager.authorize("health")
        val notifAuth = manager.authorize("notifications")

        manager.approve(healthAuth.userCode)

        val healthResult = manager.poll(healthAuth.deviceCode)
        val notifResult = manager.poll(notifAuth.deviceCode)

        assertEquals(DeviceCodeStatus.APPROVED, healthResult.status)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, notifResult.status)
    }
}
