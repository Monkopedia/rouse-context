package com.rousecontext.app.ui.viewmodels

import android.content.Intent
import com.rousecontext.app.delivery.BackgroundDelivery
import com.rousecontext.app.delivery.DeliveryActivation
import com.rousecontext.app.delivery.DistributorOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the picker view-model: re-scan behaviour (issue #463) and the
 * freshly-installed-distributor nudge (issue #480).
 *
 * #463: the installed distributor list must refresh on demand so returning from
 * a store install (the destination calls [BackgroundDeliveryViewModel.rescan] on
 * `ON_RESUME`) picks up a newly-installed app.
 *
 * #480: after the user selects a distributor, the VM waits briefly for the push
 * endpoint ([BackgroundDelivery.endpointArrived]). If it arrives promptly the
 * picker proceeds with no nudge; if it doesn't (the "stopped state" case) the VM
 * raises a nudge naming the chosen distributor; when the endpoint finally arrives
 * the nudge clears and the picker proceeds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundDeliveryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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

    @Test
    fun `endpoint arriving promptly proceeds with no nudge`() = runTest(dispatcher) {
        val delivery = FakeDelivery()
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        val vm = BackgroundDeliveryViewModel(delivery)
        val proceeded = CompletableDeferred<Unit>()
        val collector = launch { vm.proceed.first().also { proceeded.complete(Unit) } }

        vm.select("io.heckel.ntfy")
        // Endpoint shows up immediately (distributor was not in stopped state).
        delivery.endpoint.value = true
        advanceUntilIdle()

        assertNull(vm.nudge.value)
        assertTrue(proceeded.isCompleted)
        collector.cancel()
    }

    @Test
    fun `select with no endpoint within timeout shows nudge naming the distributor`() =
        runTest(dispatcher) {
            val delivery = FakeDelivery()
            delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
            val vm = BackgroundDeliveryViewModel(delivery)

            vm.select("io.heckel.ntfy")
            // Endpoint never arrives -> let the timeout elapse.
            advanceUntilIdle()

            val nudge = vm.nudge.value
            assertNotNull(nudge)
            assertEquals("ntfy", nudge!!.distributorName)
            assertEquals("io.heckel.ntfy", nudge.distributorId)
        }

    @Test
    fun `endpoint arriving after the nudge clears it and proceeds`() = runTest(dispatcher) {
        val delivery = FakeDelivery()
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        val vm = BackgroundDeliveryViewModel(delivery)
        val proceeded = CompletableDeferred<Unit>()
        val collector = launch { vm.proceed.first().also { proceeded.complete(Unit) } }

        vm.select("io.heckel.ntfy")
        advanceUntilIdle()
        assertNotNull(vm.nudge.value)

        // User opens the distributor; it leaves stopped state and the endpoint
        // finally arrives.
        delivery.endpoint.value = true
        advanceUntilIdle()

        assertNull(vm.nudge.value)
        assertTrue(proceeded.isCompleted)
        collector.cancel()
    }

    @Test
    fun `opening the distributor re-registers so a dropped REGISTER is re-issued`() =
        runTest(dispatcher) {
            val delivery = FakeDelivery()
            delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
            // The distributor is in Android's stopped state: the first REGISTER is
            // dropped (no endpoint); only the second one — issued after the user
            // opens it — mints an endpoint.
            delivery.deliverEndpointOnSelectCall = 2
            val vm = BackgroundDeliveryViewModel(delivery)
            val proceeded = CompletableDeferred<Unit>()
            val collector = launch { vm.proceed.first().also { proceeded.complete(Unit) } }

            vm.select("io.heckel.ntfy")
            advanceUntilIdle()
            // First REGISTER dropped -> nudge shown, no endpoint, no proceed.
            assertNotNull(vm.nudge.value)
            assertFalse(proceeded.isCompleted)

            // User taps "Open"; the destination launches the distributor and tells
            // the VM, which re-issues registration after a short delay.
            vm.onDistributorOpened("io.heckel.ntfy")
            advanceUntilIdle()

            assertEquals(2, delivery.selected.size)
            assertNull(vm.nudge.value)
            assertTrue(proceeded.isCompleted)
            collector.cancel()
        }

    @Test
    fun `ON_RESUME re-registers a selected distributor that has no endpoint yet`() =
        runTest(dispatcher) {
            val delivery = FakeDelivery()
            delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
            delivery.deliverEndpointOnSelectCall = 2
            val vm = BackgroundDeliveryViewModel(delivery)
            val proceeded = CompletableDeferred<Unit>()
            val collector = launch { vm.proceed.first().also { proceeded.complete(Unit) } }

            vm.select("io.heckel.ntfy")
            advanceUntilIdle()
            assertNotNull(vm.nudge.value)

            // User lingered in the distributor app, then returned -> ON_RESUME
            // re-registers the now-running distributor.
            vm.onResume()
            advanceUntilIdle()

            assertEquals(2, delivery.selected.size)
            assertNull(vm.nudge.value)
            assertTrue(proceeded.isCompleted)
            collector.cancel()
        }

    @Test
    fun `ON_RESUME does not re-register once the endpoint has arrived`() = runTest(dispatcher) {
        val delivery = FakeDelivery()
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        val vm = BackgroundDeliveryViewModel(delivery)

        vm.select("io.heckel.ntfy")
        delivery.endpoint.value = true
        advanceUntilIdle()
        assertEquals(1, delivery.selected.size)

        // Endpoint already in hand: returning to the picker only rescans.
        vm.onResume()
        advanceUntilIdle()
        assertEquals(1, delivery.selected.size)
    }

    @Test
    fun `dismissNudge hides the nudge`() = runTest(dispatcher) {
        val delivery = FakeDelivery()
        delivery.options = listOf(installed("ntfy", "io.heckel.ntfy"))
        val vm = BackgroundDeliveryViewModel(delivery)

        vm.select("io.heckel.ntfy")
        advanceUntilIdle()
        assertNotNull(vm.nudge.value)

        vm.dismissNudge()
        assertNull(vm.nudge.value)
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
        val endpoint = MutableStateFlow(false)

        /**
         * Which `selectDistributor` call delivers an endpoint, mirroring the
         * stopped-state bug: a value of 2 drops the first REGISTER (stopped
         * distributor) and only mints the endpoint on the re-register. Default is
         * "never via select" so tests that flip [endpoint] manually are unaffected.
         */
        var deliverEndpointOnSelectCall = Int.MAX_VALUE
        override val isSupported = true
        override val activation: StateFlow<DeliveryActivation> =
            MutableStateFlow(DeliveryActivation.NeedsSetup)
        override val endpointArrived: StateFlow<Boolean> = endpoint
        override fun distributorOptions(): List<DistributorOption> = options
        override fun selectDistributor(id: String) {
            selected += id
            if (selected.size >= deliverEndpointOnSelectCall) {
                endpoint.value = true
            }
        }
        override fun installIntent(option: DistributorOption): Intent? = null
        override fun activeDistributorName(): String? = null
        override fun launchIntent(id: String): Intent? = null
        override fun reRegisterIfPending() = Unit
    }
}
