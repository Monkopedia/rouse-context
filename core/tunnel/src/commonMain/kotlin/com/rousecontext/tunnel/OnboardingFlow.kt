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
    private val certificateStore: CertificateStore
) {

    /**
     * Executes the onboarding flow: registers with the relay to get a subdomain.
     */
    suspend fun execute(firebaseToken: String, fcmToken: String): OnboardingResult {
        val registerData = when (
            val response = relayApiClient.register(
                firebaseToken = firebaseToken,
                fcmToken = fcmToken
            )
        ) {
            is RelayApiResult.Success -> response.data
            is RelayApiResult.RateLimited ->
                return OnboardingResult.RateLimited(retryAfterSeconds = response.retryAfterSeconds)
            is RelayApiResult.Error ->
                return OnboardingResult.RelayError(
                    statusCode = response.statusCode,
                    message = response.message
                )
            is RelayApiResult.NetworkError ->
                return OnboardingResult.NetworkError(cause = response.cause)
        }

        return try {
            certificateStore.storeSubdomain(registerData.subdomain)
            if (registerData.integrationSecrets.isNotEmpty()) {
                certificateStore.storeIntegrationSecrets(registerData.integrationSecrets)
            }
            OnboardingResult.Success(subdomain = registerData.subdomain)
        } catch (e: Exception) {
            certificateStore.clear()
            OnboardingResult.StorageFailed(e)
        }
    }
}

sealed class OnboardingResult {
    data class Success(val subdomain: String) : OnboardingResult()

    data class RateLimited(val retryAfterSeconds: Long?) : OnboardingResult()

    data class RelayError(val statusCode: Int, val message: String) : OnboardingResult()

    data class NetworkError(val cause: Exception) : OnboardingResult()

    data class StorageFailed(val cause: Exception) : OnboardingResult()
}
