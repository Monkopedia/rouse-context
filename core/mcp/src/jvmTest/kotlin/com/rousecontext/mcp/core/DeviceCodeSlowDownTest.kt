package com.rousecontext.mcp.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class DeviceCodeSlowDownTest {

    @Test
    fun `polls at correct interval both return authorization pending`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)
        val auth = manager.authorize("health")

        val first = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, first.status)

        // Advance by exactly the interval (5 seconds)
        clock.advanceSeconds(auth.interval)

        val second = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, second.status)
    }

    @Test
    fun `fast poll returns slow down`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)
        val auth = manager.authorize("health")

        val first = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, first.status)

        // Advance less than the interval
        clock.advanceSeconds(auth.interval - 1)

        val second = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.SLOW_DOWN, second.status)
    }

    @Test
    fun `recovery after slow down returns authorization pending`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)
        val auth = manager.authorize("health")

        // First poll: pending
        manager.poll(auth.deviceCode)

        // Second poll too fast: slow_down
        clock.advanceSeconds(1)
        val slowDown = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.SLOW_DOWN, slowDown.status)

        // Wait the full interval from the last poll, then retry
        clock.advanceSeconds(auth.interval)
        val recovered = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, recovered.status)
    }

    @Test
    fun `fast poll does not prevent approval after backing off`() {
        val clock = FakeClock()
        val tokenStore = InMemoryTokenStore()
        val manager = DeviceCodeManager(tokenStore = tokenStore, clock = clock)
        val auth = manager.authorize("health")

        // First poll
        manager.poll(auth.deviceCode)

        // Fast poll triggers slow_down
        clock.advanceSeconds(1)
        val slowDown = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.SLOW_DOWN, slowDown.status)

        // Approve while backed off
        manager.approve(auth.userCode)

        // Wait proper interval, then poll - should get the approval
        clock.advanceSeconds(auth.interval)
        val result = manager.poll(auth.deviceCode)
        assertEquals(DeviceCodeStatus.APPROVED, result.status)
        assertNotNull(result.tokenPair)
    }

    @Test
    fun `slow down is per device code`() {
        val clock = FakeClock()
        val manager = DeviceCodeManager(clock = clock)
        val auth1 = manager.authorize("health")
        val auth2 = manager.authorize("notifications")

        // First poll on both
        manager.poll(auth1.deviceCode)
        manager.poll(auth2.deviceCode)

        // Fast poll on code1 only
        clock.advanceSeconds(1)
        val code1Result = manager.poll(auth1.deviceCode)
        assertEquals(DeviceCodeStatus.SLOW_DOWN, code1Result.status)

        // Code2 waits proper interval
        clock.advanceSeconds(auth2.interval)
        val code2Result = manager.poll(auth2.deviceCode)
        assertEquals(DeviceCodeStatus.AUTHORIZATION_PENDING, code2Result.status)
    }
}
