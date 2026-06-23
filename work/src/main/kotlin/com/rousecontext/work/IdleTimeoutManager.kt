package com.rousecontext.work

import com.rousecontext.tunnel.TunnelState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Disconnects the tunnel after [timeoutMillis] of idle time in CONNECTED state.
 *
 * Each entry into [TunnelState.CONNECTED] arms the idle timer. Each transition
 * to [TunnelState.ACTIVE] cancels it. This matches user intuition: 5 minutes of
 * CONNECTED without any live streams means the wake was either spurious or the
 * client has gone away, and the service should stop to save battery.
 *
 * When a full wake cycle (CONNECTED -> ... -> DISCONNECTED) completes, the
 * optional [recorder] is notified so the app can track spurious wakes (wake
 * cycles that never reached ACTIVE).
 *
 * @param timeoutProvider Supplies the idle timeout in milliseconds, read each
 *   time the timer arms (on entering CONNECTED) so a user setting change takes
 *   effect on the next wake cycle without restarting the service. Returning
 *   `null` means the idle timeout is disabled — the timer is not armed at all.
 *   The `substantive` argument reports whether this wake cycle has issued at
 *   least one `tools/call` (see [activityTracker]): callers use it to choose the
 *   long "Idle timeout" for substantive cycles versus the short "Quick
 *   disconnect" timeout for discovery-only or spurious wakes. This keeps a
 *   lightweight wake (e.g. a client that only lists tools then idles) from
 *   holding the foreground service up for the full idle timeout and burning the
 *   Android 15 dataSync budget (6h/24h).
 * @param onTimeout Called when the idle timeout fires.
 * @param recorder Optional sink for wake-cycle observability.
 * @param activityTracker Shared per-cycle substantiveness signal. The session
 *   layer flags [SessionActivityTracker.recordToolCall] on it; this manager
 *   reads it when arming and resets it when the wake cycle ends.
 */
class IdleTimeoutManager(
    private val timeoutProvider: suspend (substantive: Boolean) -> Long?,
    private val onTimeout: suspend () -> Unit,
    private val recorder: SpuriousWakeRecorder? = null,
    private val activityTracker: SessionActivityTracker = SessionActivityTracker()
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
    suspend fun observe(stateFlow: StateFlow<TunnelState>) {
        timeoutFired = false
        // Start each observe() from a clean substantiveness slate so a leftover
        // flag from a prior service incarnation cannot pin the long timeout.
        activityTracker.reset()

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
                        // Read the timeout fresh each cycle. null = disabled:
                        // leave the timer unarmed so the tunnel stays up until
                        // some other stop (FCM idle, FGS budget) intervenes.
                        // A cycle that has issued a tools/call is "substantive"
                        // and earns the long idle timeout; a discovery-only or
                        // spurious wake gets the short quick-disconnect timeout.
                        val timeoutMillis = timeoutProvider(activityTracker.isSubstantive())
                        if (timeoutMillis != null) {
                            timerJob = launch {
                                delay(timeoutMillis)
                                timeoutFired = true
                                onTimeout()
                            }
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
                        // Next wake cycle starts fresh; a tools/call must arrive
                        // again to earn the long idle timeout.
                        activityTracker.reset()
                    }
                    TunnelState.CONNECTING, TunnelState.DISCONNECTING -> {
                        // Transitional states; no timer action.
                    }
                }
            }
        }
    }
}
