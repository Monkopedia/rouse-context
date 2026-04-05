package com.rousecontext.mcp.core

/**
 * Abstraction over system clock for testability.
 */
interface Clock {
    fun currentTimeMillis(): Long
}

/**
 * Default clock that delegates to the system clock.
 */
object SystemClock : Clock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
