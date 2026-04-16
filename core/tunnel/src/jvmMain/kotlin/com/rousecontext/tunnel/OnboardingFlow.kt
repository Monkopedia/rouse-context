package com.rousecontext.tunnel

/**
 * Orchestrates first-run device onboarding: registers the device with the relay
 * to get a subdomain assignment. That's it -- certificate provisioning is deferred
 * to [CertProvisioningFlow] when the user enables their first integration.
 *
 * This avoids burning an ACME cert (50/week quota) for devices that haven't
 * set up any integrations yet.
 *
 * Guarantees atomicity: if any step fails, no partial state is persisted.
 */
class OnboardingFlow(
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore,
    private val integrationIds: List<String> = emptyList()
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
     *
     * Any failure leaves the certificate store empty.
     *
     * If step 1 succeeds but step 2 fails, the reservation will expire on
     * its own (short TTL) and the next attempt will pick a new name — no
     * explicit cleanup required.
     */
    suspend fun execute(firebaseToken: String, fcmToken: String): OnboardingResult {
        relayApiClient.requestSubdomain(firebaseToken = firebaseToken)
            .mapFailure()
            ?.let { return it }

        val registerData = relayApiClient.register(
            firebaseToken = firebaseToken,
            fcmToken = fcmToken,
            integrationIds = integrationIds
        ).let { response ->
            response.mapFailure()?.let { return it }
            (response as RelayApiResult.Success).data
        }

        return try {
            certificateStore.storeSubdomain(registerData.subdomain)
            if (registerData.secrets.isNotEmpty()) {
                certificateStore.storeIntegrationSecrets(registerData.secrets)
            }
            OnboardingResult.Success(subdomain = registerData.subdomain)
        } catch (e: Exception) {
            certificateStore.clear()
            OnboardingResult.StorageFailed(e)
        }
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
}
