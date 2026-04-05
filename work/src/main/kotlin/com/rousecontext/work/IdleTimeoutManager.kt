package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Disconnects the tunnel after [timeoutMillis] of idle time in CONNECTED state.
 *
 * "Idle" means CONNECTED with no active streams. The timer resets when a stream
 * opens (ACTIVE) and restarts when it returns to CONNECTED. The timer is disabled
 * entirely when [batteryExempt] is true (device is on unrestricted battery).
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

    /**
     * Collects [stateFlow] and manages the idle timer.
     * Suspends until cancelled.
     */
    suspend fun observe(stateFlow: kotlinx.coroutines.flow.StateFlow<TunnelState>) {
        if (batteryExempt) {
            // Just consume the flow without starting any timers
            stateFlow.collect { /* no-op */ }
            return
        }

        coroutineScope {
            var timerJob: Job? = null

            stateFlow.collect { state ->
                timerJob?.cancel()
                timerJob = null

                if (state == TunnelState.CONNECTED) {
                    timerJob = launch {
                        delay(timeoutMillis)
                        onTimeout()
                    }
                }
            }
        }
    }
}
