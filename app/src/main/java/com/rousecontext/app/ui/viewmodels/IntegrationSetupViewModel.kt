package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * State emitted by [IntegrationSetupViewModel] to track cert provisioning progress.
 */
sealed interface IntegrationSetupState {
    data object Idle : IntegrationSetupState
    data class Provisioning(val settingUpState: SettingUpState) : IntegrationSetupState
    data object Complete : IntegrationSetupState
    data class Failed(val message: String) : IntegrationSetupState
    data class RateLimited(val retryDate: String) : IntegrationSetupState
}

/**
 * Orchestrates the integration setup flow: enables the integration,
 * triggers cert provisioning if needed, and tracks progress.
 *
 * When the user enables their first integration, this triggers ACME cert
 * provisioning via [CertProvisioningFlow]. If certs already exist (e.g. the
 * user is enabling a second integration), provisioning is skipped.
 */
class IntegrationSetupViewModel(
    private val stateStore: IntegrationStateStore,
    private val certProvisioningFlow: CertProvisioningFlow,
    private val lazyWebSocketFactory: LazyWebSocketFactory
) : ViewModel() {

    private val _state = MutableStateFlow<IntegrationSetupState>(IntegrationSetupState.Idle)
    val state: StateFlow<IntegrationSetupState> = _state.asStateFlow()

    private var integrationId: String = ""

    fun startSetup(id: String) {
        integrationId = id
        stateStore.setUserEnabled(id, true)
        _state.value = IntegrationSetupState.Provisioning(
            SettingUpState(variant = SettingUpVariant.Refreshing)
        )
        beginProvisioning()
    }

    private fun beginProvisioning() {
        viewModelScope.launch {
            val firebaseToken = try {
                val user = FirebaseAuth.getInstance().currentUser
                    ?: return@launch setFailed("Not signed in. Please restart the app.")
                user.getIdToken(false).await().token
                    ?: return@launch setFailed("Failed to obtain Firebase ID token.")
            } catch (e: Exception) {
                return@launch setFailed("Authentication error: ${e.message}")
            }

            when (val result = certProvisioningFlow.execute(firebaseToken)) {
                is CertProvisioningResult.Success -> {
                    lazyWebSocketFactory.invalidate()
                    _state.value = IntegrationSetupState.Complete
                }
                is CertProvisioningResult.AlreadyProvisioned -> {
                    _state.value = IntegrationSetupState.Complete
                }
                is CertProvisioningResult.NotOnboarded -> {
                    setFailed("Device not registered. Please complete setup first.")
                }
                is CertProvisioningResult.RateLimited -> {
                    val retryDate = result.retryAfterSeconds?.let { seconds ->
                        val date = Date(System.currentTimeMillis() + seconds * MILLIS_PER_SECOND)
                        DATE_FORMAT.format(date)
                    } ?: "later"
                    _state.value = IntegrationSetupState.RateLimited(retryDate = retryDate)
                }
                is CertProvisioningResult.RelayError -> {
                    Log.e(TAG, "Relay error: ${result.statusCode} - ${result.message}")
                    setFailed("Server error: ${result.message}")
                }
                is CertProvisioningResult.NetworkError -> {
                    Log.e(TAG, "Network error", result.cause)
                    setFailed("Network error. Check your connection and try again.")
                }
                is CertProvisioningResult.KeyGenerationFailed -> {
                    Log.e(TAG, "Key generation failed", result.cause)
                    setFailed("Failed to generate device keys.")
                }
                is CertProvisioningResult.StorageFailed -> {
                    Log.e(TAG, "Storage failed", result.cause)
                    setFailed("Failed to save certificate.")
                }
            }
        }
    }

    private fun setFailed(message: String) {
        _state.value = IntegrationSetupState.Failed(message = message)
    }

    companion object {
        private const val TAG = "IntegrationSetup"
        private const val MILLIS_PER_SECOND = 1000L
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())
    }
}
