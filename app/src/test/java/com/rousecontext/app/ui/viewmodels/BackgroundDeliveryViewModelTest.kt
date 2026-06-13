package com.rousecontext.app.ui.viewmodels

import android.content.Intent
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DeliveryActivation
import com.rousecontext.app.delivery.DistributorOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the picker view-model's re-scan behaviour (issue #463): the installed
 * distributor list must refresh on demand so returning from a store install
 * (the destination calls [BackgroundDeliveryViewModel.rescan] on `ON_RESUME`)
 * picks up a newly-installed app.
 */
class BackgroundDeliveryViewModelTest {

    @Test
    fun `rescan re-enumerates distributors after one is installed`() {
        val delivery = FakeDelivery()
        delivery.options = listOf(install("ntfy"))

        val vm = BackgroundDeliveryViewModel(delivery)
        assertEquals(1, vm.rows.value.size)
        assertEquals(DistributorOption.Kind.INSTALL_NTFY, vm.rows.value[0].kind)

        // User leaves, installs ntfy, returns -> ON_RESUME triggers rescan.
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        vm.rescan()

        assertEquals(1, vm.rows.value.size)
        assertEquals(DistributorOption.Kind.INSTALLED, vm.rows.value[0].kind)
    }

    @Test
    fun `select delegates to the delivery seam and refreshes`() {
        val delivery = FakeDelivery()
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        val vm = BackgroundDeliveryViewModel(delivery)

        vm.select("io.heckel.ntfy")

        assertTrue(delivery.selected.contains("io.heckel.ntfy"))
    }

    private fun install(name: String) = DistributorOption(
        id = "x",
        name = name,
        subtitle = "",
        kind = DistributorOption.Kind.INSTALL_NTFY
    )

    private fun installed(name: String, id: String) = DistributorOption(
        id = id,
        name = name,
        subtitle = "Installed",
        kind = DistributorOption.Kind.INSTALLED
    )

    private class FakeDelivery : BackgroundDelivery {
        var options: List<DistributorOption> = emptyList()
        val selected = mutableListOf<String>()
        override val isSupported = true
        override val activation: StateFlow<DeliveryActivation> =
            MutableStateFlow(DeliveryActivation.NeedsSetup)
        override fun distributorOptions(): List<DistributorOption> = options
        override fun selectDistributor(id: String) {
            selected += id
        }
        override fun installIntent(option: DistributorOption): Intent? = null
        override fun activeDistributorName(): String? = null
    }
}
