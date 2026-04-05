package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.flow.StateFlow

/**
 * Manages wakelock acquisition/release based on tunnel state.
 *
 * Acquires during CONNECTING and ACTIVE (CPU needed for handshake and data transfer).
 * Releases during CONNECTED (idle), DISCONNECTING, and DISCONNECTED.
 *
 * Idempotent: does not double-acquire or double-release.
 */
class WakelockManager(private val wakeLock: WakeLockHandle) {

    /**
     * Collects [stateFlow] and manages the wakelock accordingly.
     * Suspends until cancelled.
     */
    suspend fun observe(stateFlow: StateFlow<TunnelState>) {
        stateFlow.collect { state ->
            val shouldHold = state == TunnelState.CONNECTING || state == TunnelState.ACTIVE
            if (shouldHold && !wakeLock.isHeld) {
                wakeLock.acquire()
            } else if (!shouldHold && wakeLock.isHeld) {
                wakeLock.release()
            }
        }
    }
}
