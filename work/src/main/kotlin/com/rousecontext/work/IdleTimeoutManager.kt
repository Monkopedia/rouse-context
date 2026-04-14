package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Disconnects the tunnel after [timeoutMillis] of idle time in CONNECTED state.
 *
 * Each entry into [TunnelState.CONNECTED] arms the idle timer. Each transition
 * to [TunnelState.ACTIVE] cancels it. This matches user intuition: 5 minutes of
 * CONNECTED without any live streams means the wake was either spurious or the
 * client has gone away, and the service should stop to save battery.
 *
 * The timer is disabled entirely when [batteryExempt] is true.
 *
 * When a full wake cycle (CONNECTED -> ... -> DISCONNECTED) completes, the
 * optional [recorder] is notified so the app can track spurious wakes (wake
 * cycles that never reached ACTIVE).
 *
 * @param timeoutMillis How long to wait in CONNECTED state before triggering disconnect.
 * @param batteryExempt If true, idle timeout is disabled.
 * @param onTimeout Called when the idle timeout fires.
 * @param recorder Optional sink for wake-cycle observability.
 */
class IdleTimeoutManager(
    private val timeoutMillis: Long,
    private val batteryExempt: Boolean,
    private val onTimeout: suspend () -> Unit,
    private val recorder: SpuriousWakeRecorder? = null
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
            // Per-cycle tracking: did this wake cycle ever reach ACTIVE?
            var hasBeenActive = false
            // Per-cycle tracking: did this cycle enter CONNECTED/ACTIVE at all?
            var cycleStarted = false

            stateFlow.collect { state ->
                timerJob?.cancel()
                timerJob = null

                when (state) {
                    TunnelState.CONNECTED -> {
                        cycleStarted = true
                        timerJob = launch {
                            delay(timeoutMillis)
                            timeoutFired = true
                            onTimeout()
                        }
                    }
                    TunnelState.ACTIVE -> {
                        cycleStarted = true
                        hasBeenActive = true
                    }
                    TunnelState.DISCONNECTED -> {
                        if (cycleStarted) {
                            recorder?.recordWakeCycle(hadActiveStream = hasBeenActive)
                        }
                        hasBeenActive = false
                        cycleStarted = false
                    }
                    TunnelState.CONNECTING, TunnelState.DISCONNECTING -> {
                        // Transitional states; no timer action.
                    }
                }
            }
        }
    }
}
