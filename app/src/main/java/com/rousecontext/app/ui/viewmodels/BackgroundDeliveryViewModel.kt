package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DistributorOption
import kotlinx.coroutines.Job
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

    /** Re-enumerate installed distributors (e.g. after returning from a store). */
    fun rescan() {
        _rows.value = delivery.distributorOptions()
    }

    /**
     * User tapped an installed distributor row: persist + register with it, then
     * watch for the endpoint. Raises [nudge] if it doesn't arrive within
     * [NUDGE_TIMEOUT_MS]; emits [proceed] once it does (immediately if it already
     * has, e.g. an already-set-up device re-picking in Settings).
     */
    fun select(id: String) {
        val name = _rows.value.firstOrNull { it.id == id }?.name ?: id
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
