package com.rousecontext.mcp.core

/**
 * Test clock that can be manually advanced for device code expiry testing.
 */
class FakeClock(private var currentTimeMs: Long = System.currentTimeMillis()) : Clock {

    override fun currentTimeMillis(): Long = currentTimeMs

    fun advanceMinutes(minutes: Int) {
        currentTimeMs += minutes * 60 * 1000L
    }

    fun advanceSeconds(seconds: Int) {
        currentTimeMs += seconds * 1000L
    }
}
