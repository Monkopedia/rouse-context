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
    data object Onboarded : OnboardingState
    data class Failed(val message: String) : OnboardingState
    data class RateLimited(val retryDate: String) : OnboardingState
}

/**
 * Determines whether the device is onboarded and drives the onboarding flow.
 *
 * On init, checks [CertificateStore.getSubdomain] to decide the initial route.
 * When the user taps "Get Started", immediately transitions to [OnboardingState.Onboarded]
 * so the user lands on Home without blocking. Firebase auth and relay registration
 * run in the background. Other parts of the app can observe [DeviceRegistrationStatus]
 * to gate operations that require a subdomain.
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
     * Immediately marks the user as onboarded (so navigation goes to Home)
     * and kicks off Firebase auth + relay registration in the background.
     */
    fun startOnboarding() {
        val current = _state.value
        if (current is OnboardingState.Onboarded) return

        // Navigate to Home immediately
        _state.value = OnboardingState.Onboarded

        // Launch registration on the Application-scoped coroutine so it survives
        // this ViewModel being cleared when the UI navigates away from the
        // onboarding screen. viewModelScope would be cancelled mid-Firebase-call.
        appScope.launch {
            performRegistration()
        }
    }

    private suspend fun performRegistration() {
        // Get Firebase anonymous auth token
        val firebaseToken = try {
            authClient.signInAnonymouslyAndGetIdToken()
                ?: return logError("Firebase authentication failed.")
        } catch (e: Exception) {
            return logError("Firebase authentication failed: ${e.message}")
        }

        // Get FCM registration token
        val fcmToken = try {
            fcmTokenProvider.currentToken()
        } catch (e: Exception) {
            return logError("Failed to obtain FCM token: ${e.message}")
        }

        Log.i(
            TAG,
            "Starting onboarding, firebaseToken=${firebaseToken.take(
                TOKEN_LOG_PREFIX
            )}..., fcmToken=${fcmToken.take(TOKEN_LOG_PREFIX)}..."
        )

        handleResult(
            onboardingFlow.execute(
                firebaseToken = firebaseToken,
                fcmToken = fcmToken
            )
        )
    }

    private fun handleResult(result: OnboardingResult) {
        when (result) {
            is OnboardingResult.Success -> {
                registrationStatus.markComplete()
            }
            is OnboardingResult.RateLimited -> {
                val retryDate = result.retryAfterSeconds?.let { seconds ->
                    val date = Date(System.currentTimeMillis() + seconds * MILLIS_PER_SECOND)
                    DATE_FORMAT.format(date)
                } ?: "later"
                Log.w(TAG, "Rate limited, retry: $retryDate")
            }
            is OnboardingResult.RelayError -> {
                Log.e(TAG, "Relay error: ${result.statusCode} - ${result.message}")
            }
            is OnboardingResult.NetworkError -> {
                Log.e(TAG, "Network error", result.cause)
            }
            is OnboardingResult.StorageFailed -> {
                Log.e(TAG, "Failed to save registration")
            }
        }
    }

    private fun logError(message: String) {
        Log.e(TAG, message)
    }

    fun retry() {
        appScope.launch {
            performRegistration()
        }
    }

    companion object {
        private const val TAG = "Onboarding"
        private const val TOKEN_LOG_PREFIX = 20
        private const val MILLIS_PER_SECOND = 1000L
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
