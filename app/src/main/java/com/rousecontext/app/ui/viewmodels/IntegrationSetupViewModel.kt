package com.rousecontext.app.ui.viewmodels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.rousecontext.api.CrashReporter
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
 * Identifies where in the setup flow the last failure occurred so [IntegrationSetupViewModel.retry]
 * can resume from the correct step instead of restarting cert provisioning.
 */
internal enum class IntegrationSetupFailureStage {
    /** Failure before or during cert provisioning (pre-cert). Retry runs the full flow. */
    CertProvisioning,

    /** Failure after certs exist, while pushing valid-secrets to the relay. Retry only re-pushes. */
    SecretsPush
}

/**
 * Orchestrates the integration setup flow: enables the integration,
 * triggers cert provisioning if needed, then pushes the updated list of
 * integration list to the relay to regenerate `{adjective}-{integrationId}`
 * secrets server-side.
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
    private val firebaseTokenProvider: suspend () -> String? = { defaultFirebaseToken() },
    private val crashReporter: CrashReporter = CrashReporter.NoOp
) : ViewModel() {

    private val _state = MutableStateFlow<IntegrationSetupState>(IntegrationSetupState.Idle)
    val state: StateFlow<IntegrationSetupState> = _state.asStateFlow()

    private var integrationId: String = ""

    /**
     * Tracks which stage last failed so [retry] can resume from the right step.
     * Null between failures. Only accessed from the main dispatcher (viewModelScope).
     */
    private var lastFailureStage: IntegrationSetupFailureStage? = null

    fun startSetup(id: String) {
        // Issue #238: IntegrationSetupDestination fires startSetup from a
        // LaunchedEffect that can re-run on recomposition/navigation. Without
        // this guard a second invocation spawns a duplicate viewModelScope
        // coroutine, which would race the first past CertProvisioningFlow's
        // already-provisioned check and issue two /register/certs requests.
        // CertProvisioningFlow also serializes via Mutex as defense-in-depth,
        // but bailing here avoids even starting the work.
        if (_state.value is IntegrationSetupState.Provisioning) return
        integrationId = id
        _state.value = IntegrationSetupState.Provisioning(
            SettingUpState(variant = SettingUpVariant.Requesting)
        )
        viewModelScope.launch {
            stateStore.setUserEnabled(id, true)
            beginProvisioningAsync()
        }
    }

    /**
     * Re-runs the step that previously failed. If cert provisioning failed, the
     * full flow is re-run (idempotent because [CertProvisioningResult.AlreadyProvisioned]
     * short-circuits when a cert already exists). If only the secrets push failed,
     * we skip cert provisioning and re-run the push directly.
     *
     * No-op if [state] is not currently [IntegrationSetupState.Failed].
     */
    fun retry() {
        if (_state.value !is IntegrationSetupState.Failed) return
        _state.value = IntegrationSetupState.Provisioning(
            SettingUpState(variant = SettingUpVariant.Requesting)
        )
        when (lastFailureStage) {
            IntegrationSetupFailureStage.SecretsPush -> {
                viewModelScope.launch { pushIntegrationSecrets() }
            }
            // CertProvisioning, or unknown (defensive) — re-run the full flow.
            else -> beginProvisioning()
        }
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
        viewModelScope.launch { beginProvisioningAsync() }
    }

    @Suppress("LongMethod")
    private suspend fun beginProvisioningAsync() {
        awaitRegistrationIfNeeded()

        val firebaseToken = try {
            firebaseTokenProvider() ?: run {
                setFailed("Failed to obtain Firebase ID token.")
                return
            }
        } catch (e: Exception) {
            setFailed("Authentication error: ${e.message}")
            return
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
                crashReporter.log(
                    "CertProvisioning relay error status=${result.statusCode} msg=${result.message}"
                )
                setFailed("Server error: ${result.message}")
            }
            is CertProvisioningResult.NetworkError -> {
                Log.e(TAG, "Network error", result.cause)
                setFailed("Network error. Check your connection and try again.")
            }
            is CertProvisioningResult.KeyGenerationFailed -> {
                Log.e(TAG, "Key generation failed", result.cause)
                // Key generation failures are terminal — surface them so we
                // can tell Keystore regressions apart from normal onboarding.
                crashReporter.logCaughtException(result.cause)
                setFailed("Failed to generate device keys.")
            }
            is CertProvisioningResult.StorageFailed -> {
                Log.e(TAG, "Storage failed", result.cause)
                // Local storage failure is rare and silently drops the cert;
                // always worth surfacing.
                crashReporter.logCaughtException(result.cause)
                setFailed("Failed to save certificate.")
            }
        }
    }

    /**
     * Pushes the current integration list to the relay so it can provision
     * `{adjective}-{integrationId}` secrets for SNI routing. The relay is the
     * sole source of truth for secret values: it regenerates the full secret
     * set for [integrationIds] on every call, returns the mapping, and the
     * device persists what the relay sent back.
     *
     * Retries transient failures with exponential backoff.
     */
    private suspend fun pushIntegrationSecrets() {
        val subdomain = certStore.getSubdomain()
        if (subdomain == null) {
            setFailed("Device not registered. Please complete setup first.")
            return
        }

        if (integrationIds.isEmpty()) {
            Log.i(TAG, "No integrations configured; skipping secrets push")
            _state.value = IntegrationSetupState.Complete
            return
        }

        var backoffMs = INITIAL_BACKOFF_MS
        repeat(SECRETS_PUSH_ATTEMPTS) { attempt ->
            when (val result = relayApiClient.updateSecrets(subdomain, integrationIds)) {
                is RelayApiResult.Success -> {
                    val newSecrets = result.data.secrets
                    try {
                        certStore.storeIntegrationSecrets(newSecrets)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to persist integration secrets from relay", e)
                        crashReporter.logCaughtException(e)
                        setFailed(
                            SECRETS_PUSH_FAILED_MESSAGE,
                            IntegrationSetupFailureStage.SecretsPush
                        )
                        return
                    }
                    Log.i(TAG, "Integration secrets pushed to relay (${newSecrets.size})")
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
        setFailed(SECRETS_PUSH_FAILED_MESSAGE, IntegrationSetupFailureStage.SecretsPush)
    }

    private fun setFailed(
        message: String,
        stage: IntegrationSetupFailureStage = IntegrationSetupFailureStage.CertProvisioning
    ) {
        lastFailureStage = stage
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
