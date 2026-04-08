package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Disconnects the tunnel after [timeoutMillis] of idle time in CONNECTED state,
 * but only after at least one stream has been active in this session.
 *
 * On initial connect, the device waits indefinitely for the first client. Once a
 * stream opens (ACTIVE) and later closes (back to CONNECTED), the idle timer starts.
 * This prevents the tunnel from disconnecting before any client has had a chance to
 * connect.
 *
 * The timer is disabled entirely when [batteryExempt] is true.
 *
 * @param timeoutMillis How long to wait in CONNECTED state before triggering disconnect.
 * @param batteryExempt If true, idle timeout is disabled.
 * @param onTimeout Called when the idle timeout fires.
 */
class IdleTimeoutManager(
    private val timeoutMillis: Long,
    private val batteryExempt: Boolean,
    private val onTimeout: suspend () -> Unit
) {

    /** True after the idle timeout has fired. Reset on each new observe() call. */
    @Volatile
    var timeoutFired: Boolean = false
        private set

    /** Reset the timeout flag so FCM wake can restart the service after idle shutdown. */
    fun resetTimeout() {
        timeoutFired = false
    }

    /**
     * Collects [stateFlow] and manages the idle timer.
     * Suspends until cancelled.
     */
    suspend fun observe(stateFlow: kotlinx.coroutines.flow.StateFlow<TunnelState>) {
        timeoutFired = false

        if (batteryExempt) {
            // Just consume the flow without starting any timers
            stateFlow.collect { /* no-op */ }
            return
        }

        coroutineScope {
            var timerJob: Job? = null
            var hasBeenActive = false

            stateFlow.collect { state ->
                timerJob?.cancel()
                timerJob = null

                if (state == TunnelState.ACTIVE) {
                    hasBeenActive = true
                } else if (state == TunnelState.CONNECTED && hasBeenActive) {
                    timerJob = launch {
                        delay(timeoutMillis)
                        timeoutFired = true
                        onTimeout()
                    }
                } else if (state == TunnelState.DISCONNECTED) {
                    hasBeenActive = false
                }
            }
        }
    }
}
