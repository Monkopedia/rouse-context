package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DistributorOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives the "Background delivery" picker (issue #463). Reads the installed
 * UnifiedPush distributors through the flavor-bound [BackgroundDelivery] seam
 * and re-scans on demand so returning from a store install refreshes the list
 * (the destination calls [rescan] on `ON_RESUME`).
 */
class BackgroundDeliveryViewModel(private val delivery: BackgroundDelivery) : ViewModel() {

    private val _rows = MutableStateFlow(delivery.distributorOptions())
    val rows: StateFlow<List<DistributorOption>> = _rows.asStateFlow()

    /** Re-enumerate installed distributors (e.g. after returning from a store). */
    fun rescan() {
        _rows.value = delivery.distributorOptions()
    }

    /** User tapped an installed distributor row: persist + register with it. */
    fun select(id: String) {
        delivery.selectDistributor(id)
        rescan()
    }
}
