package com.rousecontext.app.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            val commonName = UUID.randomUUID().toString().take(SUBDOMAIN_LENGTH)
            when (val result = onboardingFlow.execute(commonName)) {
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
                    _state.value = OnboardingState.Failed(
                        message = "Server error: ${result.message}"
                    )
                }
                is OnboardingResult.NetworkError -> {
                    _state.value = OnboardingState.Failed(
                        message = "Network error. Check your connection and try again."
                    )
                }
                is OnboardingResult.KeyGenerationFailed -> {
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

    fun retry() {
        startOnboarding()
    }

    companion object {
        private const val SUBDOMAIN_LENGTH = 12
        private const val MILLIS_PER_SECOND = 1000L
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
