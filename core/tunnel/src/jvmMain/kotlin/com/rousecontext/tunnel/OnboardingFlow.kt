package com.rousecontext.tunnel

/**
 * Orchestrates first-run device onboarding: registers the device with the relay
 * to get a subdomain assignment, then provisions the ACME server + relay CA
 * client certificates so the device is immediately usable.
 *
 * Cert provisioning is chained into onboarding (#389) because a device that has
 * a subdomain but no certs is in a half-configured state: the AI-client TLS
 * handshake EOFs (no server cert) and the mTLS WebSocket reconnect 401s (no
 * client cert). Running both hops as a single unit closes that window.
 *
 * [certProvisioningFlow] is optional so existing crash-recovery tests
 * (#163 / #272) can still simulate the "onboarded but certs pending" state by
 * wiring an instance without a provisioning flow. Production always wires
 * both halves — see `appModule`.
 *
 * Guarantees:
 *   - If subdomain registration fails, no partial state is persisted.
 *   - If cert provisioning fails, the subdomain + integration secrets are
 *     kept (#163): the user can retry cert issuance without re-burning a
 *     subdomain reservation.
 */
class OnboardingFlow(
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore,
    private val integrationIds: List<String> = emptyList(),
    private val certProvisioningFlow: CertProvisioningFlow? = null
) {

    /**
     * Executes the onboarding flow:
     *
     * 1. Reserve a subdomain via `POST /request-subdomain`. The relay picks
     *    a single-word name (issue #92) and persists a short-TTL reservation
     *    keyed by the UID embedded in [firebaseToken].
     * 2. Register via `POST /register`. The relay consumes the reservation
     *    (single-use) and returns the reserved subdomain plus the generated
     *    integration secrets map.
     * 3. Persist subdomain + secrets locally.
     * 4. If a [CertProvisioningFlow] was provided, chain
     *    `POST /register/certs` to mint the ACME server cert and relay-CA
     *    client cert (#389).
     *
     * If step 1 succeeds but step 2 fails, the reservation will expire on
     * its own (short TTL) and the next attempt will pick a new name — no
     * explicit cleanup required.
     *
     * If step 3 fails, the store is cleared (no partial onboarding). If
     * step 4 fails, the subdomain + secrets are kept so the user can retry
     * cert issuance without a fresh `/register` hop.
     */
    suspend fun execute(firebaseToken: String, fcmToken: String): OnboardingResult =
        registerAndPersist(firebaseToken, fcmToken).fold(
            onFailure = { it },
            onSuccess = { subdomain ->
                certProvisioningFlow?.let { provisioner ->
                    provisionCerts(provisioner, firebaseToken, subdomain)
                } ?: OnboardingResult.Success(subdomain = subdomain)
            }
        )

    /**
     * Runs the relay registration + local persistence half of onboarding.
     * Returns [RegisterOutcome.Success] with the assigned subdomain on success,
     * or [RegisterOutcome.Failure] wrapping the terminal [OnboardingResult]
     * the caller should propagate.
     */
    private suspend fun registerAndPersist(
        firebaseToken: String,
        fcmToken: String
    ): RegisterOutcome {
        relayApiClient.requestSubdomain(firebaseToken = firebaseToken)
            .mapFailure()
            ?.let { return RegisterOutcome.Failure(it) }

        val registerResult = relayApiClient.register(
            firebaseToken = firebaseToken,
            fcmToken = fcmToken,
            integrationIds = integrationIds
        )
        registerResult.mapFailure()?.let { return RegisterOutcome.Failure(it) }
        val registerData = (registerResult as RelayApiResult.Success).data

        return try {
            certificateStore.storeSubdomain(registerData.subdomain)
            if (registerData.secrets.isNotEmpty()) {
                certificateStore.storeIntegrationSecrets(registerData.secrets)
            }
            RegisterOutcome.Success(registerData.subdomain)
        } catch (e: Exception) {
            certificateStore.clear()
            RegisterOutcome.Failure(OnboardingResult.StorageFailed(e))
        }
    }

    private sealed interface RegisterOutcome {
        data class Success(val subdomain: String) : RegisterOutcome
        data class Failure(val result: OnboardingResult) : RegisterOutcome
    }

    private inline fun <T> RegisterOutcome.fold(
        onFailure: (OnboardingResult) -> T,
        onSuccess: (String) -> T
    ): T = when (this) {
        is RegisterOutcome.Success -> onSuccess(subdomain)
        is RegisterOutcome.Failure -> onFailure(result)
    }

    private suspend fun provisionCerts(
        provisioner: CertProvisioningFlow,
        firebaseToken: String,
        subdomain: String
    ): OnboardingResult = when (val certResult = provisioner.execute(firebaseToken)) {
        CertProvisioningResult.Success,
        CertProvisioningResult.AlreadyProvisioned ->
            OnboardingResult.Success(subdomain = subdomain)
        CertProvisioningResult.NotOnboarded ->
            // Can't happen: we just stored the subdomain above. Defensive
            // fallback so the caller still sees a failure rather than
            // appearing to succeed with no certs.
            OnboardingResult.CertRelayError(
                statusCode = 0,
                message = "Cert provisioning reported NotOnboarded",
                subdomain = subdomain
            )
        is CertProvisioningResult.RateLimited ->
            OnboardingResult.CertRateLimited(
                retryAfterSeconds = certResult.retryAfterSeconds,
                subdomain = subdomain
            )
        is CertProvisioningResult.RelayError ->
            OnboardingResult.CertRelayError(
                statusCode = certResult.statusCode,
                message = certResult.message,
                subdomain = subdomain
            )
        is CertProvisioningResult.NetworkError ->
            OnboardingResult.CertNetworkError(
                cause = certResult.cause,
                subdomain = subdomain
            )
        is CertProvisioningResult.KeyGenerationFailed ->
            OnboardingResult.CertKeyGenerationFailed(
                cause = certResult.cause,
                subdomain = subdomain
            )
        is CertProvisioningResult.StorageFailed ->
            OnboardingResult.CertStorageFailed(
                cause = certResult.cause,
                subdomain = subdomain
            )
    }

    /**
     * Map a non-success [RelayApiResult] to an [OnboardingResult]. Returns
     * null when the result is [RelayApiResult.Success], allowing callers to
     * keep a flat control flow with a single early return per failed call.
     */
    private fun <T> RelayApiResult<T>.mapFailure(): OnboardingResult? = when (this) {
        is RelayApiResult.Success -> null
        is RelayApiResult.RateLimited ->
            OnboardingResult.RateLimited(retryAfterSeconds = retryAfterSeconds)
        is RelayApiResult.Error ->
            OnboardingResult.RelayError(statusCode = statusCode, message = message)
        is RelayApiResult.NetworkError ->
            OnboardingResult.NetworkError(cause = cause)
    }
}

sealed class OnboardingResult {
    data class Success(val subdomain: String) : OnboardingResult()

    data class RateLimited(val retryAfterSeconds: Long?) : OnboardingResult()

    data class RelayError(val statusCode: Int, val message: String) : OnboardingResult()

    data class NetworkError(val cause: Exception) : OnboardingResult()

    data class StorageFailed(val cause: Exception) : OnboardingResult()

    // Cert-provisioning failures (#389). Subdomain is already persisted when
    // any of these fire, so the UI can show a retryable error that re-runs
    // just the cert step without re-registering.

    data class CertRateLimited(val retryAfterSeconds: Long?, val subdomain: String) :
        OnboardingResult()

    data class CertRelayError(val statusCode: Int, val message: String, val subdomain: String) :
        OnboardingResult()

    data class CertNetworkError(val cause: Exception, val subdomain: String) : OnboardingResult()

    data class CertKeyGenerationFailed(val cause: Exception, val subdomain: String) :
        OnboardingResult()

    data class CertStorageFailed(val cause: Exception, val subdomain: String) : OnboardingResult()
}
