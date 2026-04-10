package com.rousecontext.app.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Shared observable for whether the device has completed relay registration
 * (i.e. has a subdomain). Provided as a Koin singleton so that
 * [com.rousecontext.app.ui.viewmodels.OnboardingViewModel] can signal
 * completion and [com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel]
 * can wait for it.
 */
class DeviceRegistrationStatus(initiallyRegistered: Boolean = false) {

    private val _complete = MutableStateFlow(initiallyRegistered)
    val complete: StateFlow<Boolean> = _complete.asStateFlow()

    fun markComplete() {
        _complete.value = true
    }

    /**
     * Suspends until registration is complete. Returns immediately if already done.
     */
    suspend fun awaitComplete() {
        complete.first { it }
    }
}
