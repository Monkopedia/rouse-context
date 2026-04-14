package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Manages wakelock acquisition/release based on tunnel state.
 *
 * Acquires during CONNECTING and ACTIVE (CPU needed for handshake and data transfer).
 * Releases during DISCONNECTING and DISCONNECTED.
 *
 * On CONNECTED (idle), schedules a release after [CONNECTED_GRACE_MS]. This protects against
 * the CONNECTED→ACTIVE race where the relay pushes the first request frame moments after the
 * TLS handshake completes: releasing immediately would let Doze defer that inbound packet on
 * non-battery-exempt devices. If ACTIVE arrives within the grace window, the pending release
 * is cancelled. If DISCONNECTING/DISCONNECTED arrives, we cancel and release immediately.
 *
 * Idempotent: does not double-acquire or double-release.
 */
class WakelockManager(private val wakeLock: WakeLockHandle) {

    /**
     * Collects [stateFlow] and manages the wakelock accordingly.
     * Suspends until cancelled.
     */
    suspend fun observe(stateFlow: StateFlow<TunnelState>) {
        var pendingReleaseJob: Job? = null
        coroutineScope {
            stateFlow.collect { state ->
                pendingReleaseJob?.cancel()
                pendingReleaseJob = null
                when (state) {
                    TunnelState.CONNECTING, TunnelState.ACTIVE ->
                        if (!wakeLock.isHeld) wakeLock.acquire()
                    TunnelState.CONNECTED ->
                        pendingReleaseJob = launch {
                            delay(CONNECTED_GRACE_MS)
                            if (wakeLock.isHeld) wakeLock.release()
                        }
                    TunnelState.DISCONNECTING, TunnelState.DISCONNECTED ->
                        if (wakeLock.isHeld) wakeLock.release()
                }
            }
        }
    }

    companion object {
        /**
         * Grace period after CONNECTED before releasing the wakelock.
         *
         * Covers the CONNECTED→ACTIVE window where the relay's first request frame may arrive.
         * 3s comfortably exceeds normal relay-to-client first-request latency (10-200ms) while
         * adding negligible battery cost per wake cycle.
         */
        const val CONNECTED_GRACE_MS = 3_000L
    }
}
