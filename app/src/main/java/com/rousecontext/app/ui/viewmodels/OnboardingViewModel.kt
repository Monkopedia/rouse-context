package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rousecontext.app.auth.AnonymousAuthClient
import com.rousecontext.app.auth.FcmTokenProvider
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface OnboardingState {
    data object Checking : OnboardingState
    data object NotOnboarded : OnboardingState

    /**
     * Onboarding is in flight. [step] tells the UI which phase to label:
     * initial relay registration, or the longer ACME cert provisioning hop.
     */
    data class InProgress(val step: OnboardingStep) : OnboardingState
    data object Onboarded : OnboardingState
    data class Failed(val message: String) : OnboardingState
    data class RateLimited(val retryDate: String) : OnboardingState
}

/**
 * The step currently running inside [OnboardingState.InProgress]. The UI maps
 * these to different copy ("Registering" vs "Provisioning certificates") so
 * the user sees progress during the multi-second ACME hop added by #389.
 */
enum class OnboardingStep {
    Registering,
    ProvisioningCerts
}

/**
 * Determines whether the device is onboarded and drives the onboarding flow.
 *
 * On init, checks [CertificateStore.getSubdomain] to decide the initial route.
 * When the user taps "Get Started", we transition to
 * [OnboardingState.InProgress] while Firebase auth, relay registration, and
 * ACME cert provisioning (#389) run on the Application-scoped coroutine. Only
 * after the full flow succeeds do we advance to [OnboardingState.Onboarded]
 * so navigation goes to Home. Failures land in [OnboardingState.Failed] or
 * [OnboardingState.RateLimited] with a retry entry point rather than silently
 * dropping the user onto a half-configured dashboard.
 */
class OnboardingViewModel(
    private val certificateStore: CertificateStore,
    private val onboardingFlow: OnboardingFlow,
    private val registrationStatus: DeviceRegistrationStatus,
    private val authClient: AnonymousAuthClient,
    private val fcmTokenProvider: FcmTokenProvider,
    private val appScope: CoroutineScope
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Checking)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val subdomain = certificateStore.getSubdomain()
            if (subdomain != null) {
                registrationStatus.markComplete()
                _state.value = OnboardingState.Onboarded
            } else {
                _state.value = OnboardingState.NotOnboarded
            }
        }
    }

    /**
     * Flips to [OnboardingState.InProgress] and kicks off the onboarding flow
     * (Firebase auth → relay registration → ACME cert provisioning) on
     * [appScope] so the work survives this ViewModel being cleared if the UI
     * recomposes or navigates. Only advances to [OnboardingState.Onboarded]
     * after the full flow — including cert provisioning — succeeds.
     */
    fun startOnboarding() {
        val current = _state.value
        if (current is OnboardingState.Onboarded) return
        if (current is OnboardingState.InProgress) return

        _state.value = OnboardingState.InProgress(OnboardingStep.Registering)

        // Launch on the Application-scoped coroutine so we don't get killed
        // if the user backgrounds the app or navigates around during the
        // several-second ACME hop. viewModelScope would be cancelled if the
        // host composable leaves composition.
        appScope.launch {
            performRegistration()
        }
    }

    private suspend fun performRegistration() {
        // Get Firebase anonymous auth token
        val firebaseToken = try {
            authClient.signInAnonymouslyAndGetIdToken()
                ?: return fail("Couldn't sign in. Check your connection and try again.")
        } catch (e: Exception) {
            return fail("Authentication error: ${e.message ?: "unknown"}")
        }

        // Get FCM registration token
        val fcmToken = try {
            fcmTokenProvider.currentToken()
        } catch (e: Exception) {
            return fail("Couldn't reach Firebase Cloud Messaging: ${e.message ?: "unknown"}")
        }

        // scrub: previously logged 20-char token prefixes (see #379). Firebase ID
        // tokens and FCM tokens are bearer credentials -- even prefixes are
        // sensitive because logcat is reachable via adb / READ_LOGS, and FCM
        // tokens can be used to replay wake events against the relay.
        Log.i(
            TAG,
            "Starting onboarding, firebaseToken=${firebaseToken.length} chars, " +
                "fcmToken=${fcmToken.length} chars"
        )

        // Surface the longer cert-provisioning hop to the UI so the user sees
        // progress rather than a stuck spinner.
        _state.value = OnboardingState.InProgress(OnboardingStep.ProvisioningCerts)

        handleResult(
            onboardingFlow.execute(
                firebaseToken = firebaseToken,
                fcmToken = fcmToken
            )
        )
    }

    @Suppress("LongMethod")
    private fun handleResult(result: OnboardingResult) {
        when (result) {
            is OnboardingResult.Success -> {
                registrationStatus.markComplete()
                _state.value = OnboardingState.Onboarded
            }
            is OnboardingResult.RateLimited -> {
                Log.w(TAG, "Rate limited on register, retryAfter=${result.retryAfterSeconds}")
                _state.value = OnboardingState.RateLimited(
                    retryDate = formatRetryDate(result.retryAfterSeconds)
                )
            }
            is OnboardingResult.RelayError -> {
                Log.e(TAG, "Relay error: ${result.statusCode} - ${result.message}")
                fail("Server error (${result.statusCode}). Please try again.")
            }
            is OnboardingResult.NetworkError -> {
                Log.e(TAG, "Network error", result.cause)
                fail("Network error. Check your connection and try again.")
            }
            is OnboardingResult.StorageFailed -> {
                Log.e(TAG, "Failed to save registration", result.cause)
                fail("Couldn't save registration. Try again.")
            }
            // Cert provisioning failures (#389). Subdomain is persisted but
            // certs are not — retry re-runs the full flow; OnboardingFlow
            // short-circuits on the already-registered device and only
            // re-attempts cert issuance via CertProvisioningFlow's
            // AlreadyProvisioned check.
            is OnboardingResult.CertRateLimited -> {
                Log.w(
                    TAG,
                    "Cert provisioning rate limited, retryAfter=${result.retryAfterSeconds}"
                )
                registrationStatus.markComplete()
                _state.value = OnboardingState.RateLimited(
                    retryDate = formatRetryDate(result.retryAfterSeconds)
                )
            }
            is OnboardingResult.CertRelayError -> {
                Log.e(
                    TAG,
                    "Cert provisioning relay error: ${result.statusCode} - ${result.message}"
                )
                registrationStatus.markComplete()
                fail("Certificate server error (${result.statusCode}). Try again.")
            }
            is OnboardingResult.CertNetworkError -> {
                Log.e(TAG, "Cert provisioning network error", result.cause)
                registrationStatus.markComplete()
                fail("Network error while issuing certificate. Check your connection and retry.")
            }
            is OnboardingResult.CertKeyGenerationFailed -> {
                Log.e(TAG, "Cert provisioning key-gen failed", result.cause)
                registrationStatus.markComplete()
                fail("Couldn't generate device keys. Please try again.")
            }
            is OnboardingResult.CertStorageFailed -> {
                Log.e(TAG, "Cert provisioning storage failed", result.cause)
                registrationStatus.markComplete()
                fail("Couldn't save certificate. Please try again.")
            }
        }
    }

    private fun fail(message: String) {
        _state.value = OnboardingState.Failed(message)
    }

    private fun formatRetryDate(retryAfterSeconds: Long?): String =
        retryAfterSeconds?.let { seconds ->
            val date = Date(System.currentTimeMillis() + seconds * MILLIS_PER_SECOND)
            DATE_FORMAT.format(date)
        } ?: "later"

    fun retry() {
        val current = _state.value
        if (current is OnboardingState.InProgress) return
        if (current is OnboardingState.Onboarded) return
        _state.value = OnboardingState.InProgress(OnboardingStep.Registering)
        appScope.launch {
            performRegistration()
        }
    }

    companion object {
        private const val TAG = "Onboarding"
        private const val MILLIS_PER_SECOND = 1000L
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
