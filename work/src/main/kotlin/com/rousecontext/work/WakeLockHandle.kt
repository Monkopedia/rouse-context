package com.rousecontext.work

import android.os.PowerManager

/**
 * Abstraction over [PowerManager.WakeLock] for testability.
 */
interface WakeLockHandle {
    val isHeld: Boolean
    fun acquire()
    fun release()
}

/**
 * Production implementation wrapping a real [PowerManager.WakeLock].
 */
class RealWakeLockHandle(private val wakeLock: PowerManager.WakeLock) : WakeLockHandle {
    override val isHeld: Boolean get() = wakeLock.isHeld
    override fun acquire() = wakeLock.acquire(WAKELOCK_TIMEOUT_MS)
    override fun release() = wakeLock.release()

    private companion object {
        /** Maximum hold time as a safety net. 10 minutes. */
        const val WAKELOCK_TIMEOUT_MS = 10 * 60 * 1000L
    }
}
