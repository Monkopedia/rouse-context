package com.rousecontext.app.integration

import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.integration.harness.AppIntegrationTestHarness
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.ui.viewmodels.IntegrationSetupState
import com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * Shared plumbing for the enable / disable / re-enable integration scenarios
 * (issues #274, #275, #276). Each helper is a plain extension on
 * [AppIntegrationTestHarness] or a top-level function so the individual
 * tests stay focused on the specific assertion they add.
 *
 * The helpers deliberately bypass Koin's [IntegrationSetupViewModel] binding
 * — Koin binds `integrationIds` to the full configured list (`List<McpIntegration>`)
 * which prevents the "only push the enabled subset" assertion in #275 and #276.
 * Tests construct the VM directly with whichever subset their scenario requires.
 */
internal const val FLIP_SETUP_TIMEOUT_MS = 60_000L

/**
 * Build an [IntegrationSetupViewModel] wired to the harness Koin graph but
 * with a caller-chosen [integrationIds] payload. Use this when a scenario
 * needs to push only a subset of configured integrations (e.g. after a
 * disable flip).
 */
internal fun AppIntegrationTestHarness.buildSetupViewModel(
    integrationIds: List<String>
): IntegrationSetupViewModel {
    val registrationStatus: DeviceRegistrationStatus = koin.get()
    // `provisionDevice()` writes the subdomain, but the async
    // DeviceRegistrationStatus initializer may not have observed it yet;
    // mark complete explicitly so awaitRegistrationIfNeeded() short-circuits.
    registrationStatus.markComplete()
    return IntegrationSetupViewModel(
        stateStore = koin.get(),
        certProvisioningFlow = koin.get(),
        lazyWebSocketFactory = koin.get<LazyWebSocketFactory>(),
        registrationStatus = registrationStatus,
        relayApiClient = koin.get<RelayApiClient>(),
        certStore = koin.get<CertificateStore>(),
        integrationIds = integrationIds,
        firebaseTokenProvider = { TEST_FIREBASE_TOKEN }
    )
}

/**
 * Drive an [IntegrationSetupViewModel] through [IntegrationSetupViewModel.startSetup]
 * to [IntegrationSetupState.Complete]. Fails the test with the captured state
 * if the VM ends in [IntegrationSetupState.Failed] / [IntegrationSetupState.RateLimited]
 * rather than letting the test wedge on an infinite suspend.
 */
internal suspend fun IntegrationSetupViewModel.runSetupToComplete(integrationId: String) {
    startSetup(integrationId)
    withTimeout(FLIP_SETUP_TIMEOUT_MS) {
        val terminal = state.first { state ->
            state is IntegrationSetupState.Complete ||
                state is IntegrationSetupState.Failed ||
                state is IntegrationSetupState.RateLimited
        }
        check(terminal is IntegrationSetupState.Complete) {
            "IntegrationSetupViewModel ended in $terminal, expected Complete"
        }
    }
}
