package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DistributorOption
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Drives the "Background delivery" picker (issue #463). Reads the installed
 * UnifiedPush distributors through the flavor-bound [BackgroundDelivery] seam
 * and re-scans on demand so returning from a store install refreshes the list
 * (the destination calls [rescan] on `ON_RESUME`).
 *
 * ## Stopped-state nudge (issue #480)
 *
 * A freshly-installed distributor sits in Android's "stopped" state and ignores
 * broadcasts — including our UnifiedPush `REGISTER` — until it's launched once.
 * So a user can pick a distributor and have no endpoint ever arrive, leaving the
 * device silently degraded. After [select], the VM waits a short time for the
 * endpoint ([BackgroundDelivery.endpointArrived]); if it doesn't arrive it
 * raises a [nudge] prompting the user to open the chosen distributor (which
 * clears the stopped state). When the endpoint finally arrives the nudge clears
 * and the picker advances via [proceed]. If the endpoint arrives promptly there
 * is no nudge and the picker advances immediately.
 *
 * ## Re-register after opening (issue #493)
 *
 * Launching the distributor is not enough on its own: the `REGISTER` from
 * [select] was already dropped while the distributor was stopped, and UnifiedPush
 * never redelivers it, so the now-running distributor has nothing to act on. The
 * nudge's "Open" action therefore re-issues registration shortly after launch
 * ([onDistributorOpened]), and the destination's `ON_RESUME` ([onResume]) does
 * the same if the user returns with a selected-but-unacked distributor.
 */
class BackgroundDeliveryViewModel(private val delivery: BackgroundDelivery) : ViewModel() {

    private val _rows = MutableStateFlow(delivery.distributorOptions())
    val rows: StateFlow<List<DistributorOption>> = _rows.asStateFlow()

    private val _nudge = MutableStateFlow<DistributorNudge?>(null)

    /** Non-null while the "Open {distributor} to enable" nudge should show (#480). */
    val nudge: StateFlow<DistributorNudge?> = _nudge.asStateFlow()

    private val _proceed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits once a selected distributor's endpoint has arrived: advance the flow. */
    val proceed: SharedFlow<Unit> = _proceed.asSharedFlow()

    private var selectionJob: Job? = null
    private var reregisterJob: Job? = null

    /** The last distributor the user selected, awaiting its endpoint (#493). */
    private var selectedId: String? = null

    /** Re-enumerate installed distributors (e.g. after returning from a store). */
    fun rescan() {
        _rows.value = delivery.distributorOptions()
    }

    /**
     * Destination `ON_RESUME`: re-scan installed distributors, and — if a
     * distributor was selected but no endpoint has arrived yet — re-issue its
     * registration (#493). This is the belt-and-suspenders companion to
     * [onDistributorOpened] for when the user lingers in the distributor app past
     * the [onDistributorOpened] delay and only returns later: by then the
     * distributor has left Android's stopped state, so the re-issued `REGISTER`
     * is no longer dropped. No-op once the endpoint is in hand.
     */
    fun onResume() {
        rescan()
        val id = selectedId
        if (id != null && !delivery.endpointArrived.value) {
            delivery.selectDistributor(id)
        }
    }

    /**
     * Nudge "Open" tapped (#493): the destination has just launched the chosen
     * distributor. The original `REGISTER` from [select] was dropped because the
     * distributor was in Android's stopped state, and UnifiedPush does not
     * redeliver it — so launching alone never mints an endpoint. Give the
     * distributor a moment to leave the stopped state, then re-issue registration
     * via the same path [select] uses (re-selecting the row is the known-good
     * recovery). The in-flight [selectionJob] is still awaiting the endpoint, so
     * once it arrives the nudge clears and [proceed] fires. If it still doesn't
     * arrive the nudge stays up, so the user can retry — it never gets stuck.
     */
    fun onDistributorOpened(id: String) {
        selectedId = id
        reregisterJob?.cancel()
        reregisterJob = viewModelScope.launch {
            delay(REREGISTER_DELAY_MS)
            delivery.selectDistributor(id)
        }
    }

    /**
     * User tapped an installed distributor row: persist + register with it, then
     * watch for the endpoint. Raises [nudge] if it doesn't arrive within
     * [NUDGE_TIMEOUT_MS]; emits [proceed] once it does (immediately if it already
     * has, e.g. an already-set-up device re-picking in Settings).
     */
    fun select(id: String) {
        val name = _rows.value.firstOrNull { it.id == id }?.name ?: id
        selectedId = id
        delivery.selectDistributor(id)
        rescan()
        selectionJob?.cancel()
        selectionJob = viewModelScope.launch {
            val arrived = withTimeoutOrNull(NUDGE_TIMEOUT_MS) {
                delivery.endpointArrived.first { it }
            } != null
            if (!arrived) {
                // Stopped-state case: prompt the user to open the distributor,
                // then keep waiting for the endpoint it mints once launched.
                _nudge.value = DistributorNudge(distributorId = id, distributorName = name)
                delivery.endpointArrived.first { it }
            }
            _nudge.value = null
            _proceed.emit(Unit)
        }
    }

    /** Dismiss the nudge without launching the distributor (it stays degraded). */
    fun dismissNudge() {
        _nudge.value = null
    }

    companion object {
        /**
         * How long to wait for the endpoint after a selection before nudging.
         * Endpoint delivery from a healthy (already-launched) distributor is a
         * few seconds; past this we assume the stopped-state silent degrade.
         */
        private const val NUDGE_TIMEOUT_MS = 6_000L

        /**
         * Pause after launching the distributor before re-issuing registration
         * (#493). Long enough for the just-launched distributor to leave Android's
         * stopped state and start receiving broadcasts, short enough to feel
         * immediate. The `ON_RESUME` re-register ([onResume]) covers the case
         * where the user lingers longer than this.
         */
        private const val REREGISTER_DELAY_MS = 1_500L
    }
}

/**
 * The picker's "open the chosen distributor" nudge (issue #480): the distributor
 * the user selected but which hasn't reported an endpoint yet.
 */
data class DistributorNudge(
    /** UnifiedPush distributor package id, used to launch it. */
    val distributorId: String,
    /** Display name for the "Open {name} to enable" copy. */
    val distributorName: String
)
