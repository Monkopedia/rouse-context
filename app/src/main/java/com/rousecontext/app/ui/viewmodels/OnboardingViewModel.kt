package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.OnboardingFlow
import com.rousecontext.tunnel.OnboardingResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface OnboardingState {
    data object Checking : OnboardingState
    data object NotOnboarded : OnboardingState
    data object Onboarded : OnboardingState
    data object InProgress : OnboardingState
    data class Failed(val message: String) : OnboardingState
    data class RateLimited(val retryDate: String) : OnboardingState
}

/**
 * Determines whether the device is onboarded and drives the onboarding flow.
 *
 * On init, checks [CertificateStore.getSubdomain] to decide the initial route.
 * When the user taps "Get Started", triggers [OnboardingFlow.execute] and
 * updates state as provisioning progresses.
 */
class OnboardingViewModel(
    private val certificateStore: CertificateStore,
    private val onboardingFlow: OnboardingFlow
) : ViewModel() {

    private val _state = MutableStateFlow<OnboardingState>(OnboardingState.Checking)
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val subdomain = certificateStore.getSubdomain()
            _state.value = if (subdomain != null) {
                OnboardingState.Onboarded
            } else {
                OnboardingState.NotOnboarded
            }
        }
    }

    fun startOnboarding() {
        if (_state.value == OnboardingState.InProgress) return

        _state.value = OnboardingState.InProgress
        viewModelScope.launch {
            // Get Firebase anonymous auth token
            val firebaseToken = try {
                val user = FirebaseAuth.getInstance().signInAnonymously().await().user
                    ?: return@launch setFailed("Firebase authentication failed.")
                user.getIdToken(false).await().token
                    ?: return@launch setFailed("Failed to obtain Firebase ID token.")
            } catch (e: Exception) {
                return@launch setFailed("Firebase authentication failed: ${e.message}")
            }

            // Get FCM registration token
            val fcmToken = try {
                FirebaseMessaging.getInstance().token.await()
            } catch (e: Exception) {
                return@launch setFailed("Failed to obtain FCM token: ${e.message}")
            }

            Log.i(
                "Onboarding",
                "Starting onboarding, firebaseToken=${firebaseToken.take(
                    20
                )}..., fcmToken=${fcmToken.take(20)}..."
            )
            when (val result = onboardingFlow.execute(firebaseToken, fcmToken)) {
                is OnboardingResult.Success -> {
                    _state.value = OnboardingState.Onboarded
                }
                is OnboardingResult.RateLimited -> {
                    val retryDate = result.retryAfterSeconds?.let { seconds ->
                        val date = Date(System.currentTimeMillis() + seconds * MILLIS_PER_SECOND)
                        DATE_FORMAT.format(date)
                    } ?: "later"
                    _state.value = OnboardingState.RateLimited(retryDate = retryDate)
                }
                is OnboardingResult.RelayError -> {
                    Log.e("Onboarding", "Relay error: ${result.statusCode} - ${result.message}")
                    _state.value = OnboardingState.Failed(
                        message = "Server error: ${result.message}"
                    )
                }
                is OnboardingResult.NetworkError -> {
                    Log.e("Onboarding", "Network error", result.cause)
                    _state.value = OnboardingState.Failed(
                        message = "Network error. Check your connection and try again."
                    )
                }
                is OnboardingResult.KeyGenerationFailed -> {
                    Log.e("Onboarding", "Key generation failed", result.cause)
                    _state.value = OnboardingState.Failed(
                        message = "Failed to generate device keys."
                    )
                }
                is OnboardingResult.StorageFailed -> {
                    _state.value = OnboardingState.Failed(
                        message = "Failed to save certificate."
                    )
                }
            }
        }
    }

    private fun setFailed(message: String) {
        _state.value = OnboardingState.Failed(message = message)
    }

    fun retry() {
        startOnboarding()
    }

    companion object {
        private const val SUBDOMAIN_LENGTH = 12
        private const val MILLIS_PER_SECOND = 1000L
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
