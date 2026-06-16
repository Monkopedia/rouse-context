package com.rousecontext.app.state

import com.rousecontext.app.delivery.DeliveryActivation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Carries the id of the integration a user was adding across a Background
 * delivery picker detour (issue #474, Option A).
 *
 * On a not-yet-registered foss device (one that tapped "Skip for now" on
 * Background delivery), tapping "Add integration" used to silently block in
 * [com.rousecontext.app.ui.viewmodels.IntegrationSetupViewModel]
 * `.awaitRegistrationIfNeeded` — it read like a hang. Instead the add-integration
 * destination now [remember]s the chosen integration and redirects to the
 * picker. Once a distributor is selected, the picker [consume]s the id and
 * navigates straight into that integration's setup flow (auto-resume), rather
 * than dropping the user back at Home.
 *
 * A single pending id is held (the user can only be adding one integration at a
 * time); [remember] overwrites any prior value. Provided as a Koin singleton so
 * the two destinations share the same instance across the round-trip.
 */
class PendingIntegrationSetup {

    private val _pendingId = MutableStateFlow<String?>(null)

    /** Currently-pending integration id, or null. Drives the picker's strip. */
    val pendingId: StateFlow<String?> = _pendingId.asStateFlow()

    /** Record the integration the user is adding before redirecting to the picker. */
    fun remember(id: String) {
        _pendingId.value = id
    }

    /**
     * Return and clear the pending id (the auto-resume path). Returns null when
     * the picker was reached for any other reason (onboarding, Settings).
     */
    fun consume(): String? = _pendingId.value.also { _pendingId.value = null }

    /** Drop the pending id without resuming (e.g. the user skipped the picker). */
    fun clear() {
        _pendingId.value = null
    }
}

/**
 * Whether tapping "Add integration" must first route the user through the
 * Background delivery picker. True only for the foss "skipped delivery, not yet
 * registered" state ([DeliveryActivation.NeedsSetup]); a registered foss device
 * ([DeliveryActivation.Active]) and google/FCM
 * ([DeliveryActivation.NotApplicable]) proceed straight into setup, so this is a
 * no-op there.
 */
fun deliveryNeedsSetupBeforeIntegration(activation: DeliveryActivation): Boolean =
    activation == DeliveryActivation.NeedsSetup
