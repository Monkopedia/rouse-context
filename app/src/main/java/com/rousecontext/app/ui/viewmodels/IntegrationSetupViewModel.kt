package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.api.IntegrationStateStore
import com.rousecontext.app.cert.LazyWebSocketFactory
import com.rousecontext.app.state.DeviceRegistrationStatus
import com.rousecontext.app.ui.screens.SettingUpState
import com.rousecontext.app.ui.screens.SettingUpVariant
import com.rousecontext.tunnel.CertProvisioningFlow
import com.rousecontext.tunnel.CertProvisioningResult
import com.rousecontext.tunnel.CertificateStore
import com.rousecontext.tunnel.RelayApiClient
import com.rousecontext.tunnel.RelayApiResult
import com.rousecontext.tunnel.SecretGenerator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
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
 * triggers cert provisioning if needed, then pushes the updated list of
 * integration secrets (`valid_secrets`) to the relay.
 *
 * When the user enables their first integration, this triggers ACME cert
 * provisioning via [CertProvisioningFlow]. If certs already exist (e.g. the
 * user is enabling a second integration), provisioning is skipped.
 *
 * After cert provisioning succeeds (or is already done), the relay is
 * notified of the full current valid-secrets list so it can route incoming
 * TLS connections for the newly-enabled integration. Without this push the
 * relay's Firestore record lags behind the on-device cert store and the
 * first AI-client connection fails.
 */
@Suppress("LongParameterList")
class IntegrationSetupViewModel(
    private val stateStore: IntegrationStateStore,
    private val certProvisioningFlow: CertProvisioningFlow,
    private val lazyWebSocketFactory: LazyWebSocketFactory,
    private val registrationStatus: DeviceRegistrationStatus,
    private val relayApiClient: RelayApiClient,
    private val certStore: CertificateStore,
    private val integrationIds: List<String>,
    private val firebaseTokenProvider: suspend () -> String? = { defaultFirebaseToken() }
) : ViewModel() {

    private val _state = MutableStateFlow<IntegrationSetupState>(IntegrationSetupState.Idle)
    val state: StateFlow<IntegrationSetupState> = _state.asStateFlow()

    private var integrationId: String = ""

    fun startSetup(id: String) {
        integrationId = id
        stateStore.setUserEnabled(id, true)
        _state.value = IntegrationSetupState.Provisioning(
            SettingUpState(variant = SettingUpVariant.Requesting)
        )
        beginProvisioning()
    }

    /**
     * If device registration is still in progress (new user who just
     * tapped "Get Started"), show a registering indicator and wait.
     */
    private suspend fun awaitRegistrationIfNeeded() {
        if (!registrationStatus.complete.value) {
            _state.value = IntegrationSetupState.Provisioning(
                SettingUpState(variant = SettingUpVariant.Registering)
            )
            registrationStatus.awaitComplete()
            _state.value = IntegrationSetupState.Provisioning(
                SettingUpState(variant = SettingUpVariant.Requesting)
            )
        }
    }

    private fun beginProvisioning() {
        viewModelScope.launch {
            awaitRegistrationIfNeeded()

            val firebaseToken = try {
                firebaseTokenProvider()
                    ?: return@launch setFailed("Failed to obtain Firebase ID token.")
            } catch (e: Exception) {
                return@launch setFailed("Authentication error: ${e.message}")
            }

            when (val result = certProvisioningFlow.execute(firebaseToken)) {
                is CertProvisioningResult.Success -> {
                    lazyWebSocketFactory.invalidate()
                    pushIntegrationSecrets()
                }
                is CertProvisioningResult.AlreadyProvisioned -> {
                    pushIntegrationSecrets()
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

    /**
     * Pushes the current valid-secrets list to the relay so the newly-enabled
     * integration is accepted by SNI routing. Generates any missing secrets
     * locally, persists the merged map back to the cert store, and retries
     * transient failures.
     */
    private suspend fun pushIntegrationSecrets() {
        val subdomain = certStore.getSubdomain()
        if (subdomain == null) {
            setFailed("Device not registered. Please complete setup first.")
            return
        }

        val existing = certStore.getIntegrationSecrets().orEmpty()
        val merged = buildMergedSecrets(existing)

        // Persist any newly-generated secrets before pushing so a crash between
        // push and persist can't drop them.
        if (merged != existing) {
            runCatching { certStore.storeIntegrationSecrets(merged) }
                .onFailure {
                    Log.e(TAG, "Failed to persist merged integration secrets", it)
                    setFailed(SECRETS_PUSH_FAILED_MESSAGE)
                    return
                }
        }

        val validSecrets = merged.values.toList()
        var backoffMs = INITIAL_BACKOFF_MS
        repeat(SECRETS_PUSH_ATTEMPTS) { attempt ->
            when (val result = relayApiClient.updateSecrets(subdomain, validSecrets)) {
                is RelayApiResult.Success -> {
                    Log.i(TAG, "Integration secrets pushed to relay (${validSecrets.size})")
                    _state.value = IntegrationSetupState.Complete
                    return
                }
                is RelayApiResult.RateLimited -> {
                    Log.w(TAG, "updateSecrets rate-limited on attempt ${attempt + 1}")
                }
                is RelayApiResult.Error -> {
                    Log.w(
                        TAG,
                        "updateSecrets error on attempt ${attempt + 1}: " +
                            "${result.statusCode} ${result.message}"
                    )
                }
                is RelayApiResult.NetworkError -> {
                    Log.w(
                        TAG,
                        "updateSecrets network error on attempt ${attempt + 1}",
                        result.cause
                    )
                }
            }
            if (attempt < SECRETS_PUSH_ATTEMPTS - 1) {
                delay(backoffMs)
                backoffMs *= BACKOFF_FACTOR
            }
        }
        setFailed(SECRETS_PUSH_FAILED_MESSAGE)
    }

    /**
     * Merge existing secrets with freshly-generated ones for any configured
     * integration id that doesn't yet have a secret. Secrets for integrations
     * no longer in [integrationIds] are preserved — the relay's valid_secrets
     * list is a superset, not a mirror of installed integrations.
     */
    private fun buildMergedSecrets(existing: Map<String, String>): Map<String, String> {
        val merged = existing.toMutableMap()
        for (id in integrationIds) {
            if (merged[id].isNullOrEmpty()) {
                merged[id] = SecretGenerator.generate(id)
            }
        }
        return merged
    }

    private fun setFailed(message: String) {
        _state.value = IntegrationSetupState.Failed(message = message)
    }

    companion object {
        private const val TAG = "IntegrationSetup"
        private const val MILLIS_PER_SECOND = 1000L
        internal const val SECRETS_PUSH_ATTEMPTS = 3
        internal const val INITIAL_BACKOFF_MS = 1_000L
        internal const val BACKOFF_FACTOR = 2L
        internal const val SECRETS_PUSH_FAILED_MESSAGE =
            "Couldn't register integration with relay. Try again."
        private val DATE_FORMAT = SimpleDateFormat("MMM d", Locale.getDefault())

        private suspend fun defaultFirebaseToken(): String? {
            val user = FirebaseAuth.getInstance().currentUser ?: return null
            return user.getIdToken(false).await().token
        }
    }
}
