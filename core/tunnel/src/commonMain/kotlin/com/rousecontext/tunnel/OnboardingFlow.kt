package com.rousecontext.tunnel

/**
 * Orchestrates first-run device onboarding: keypair generation, CSR creation,
 * relay registration, and certificate storage.
 *
 * Guarantees atomicity: if any step fails, no partial state is persisted.
 */
class OnboardingFlow(
    private val csrGenerator: CsrGenerator,
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore
) {

    suspend fun execute(
        commonName: String,
        firebaseToken: String,
        fcmToken: String
    ): OnboardingResult {
        // Step 1: Generate keypair and CSR
        val csrResult = try {
            csrGenerator.generate(commonName)
        } catch (e: Exception) {
            return OnboardingResult.KeyGenerationFailed(e)
        }

        // Step 2: Call /register on relay
        return when (
            val response = relayApiClient.register(
                csrPem = csrResult.csrPem,
                firebaseToken = firebaseToken,
                fcmToken = fcmToken
            )
        ) {
            is RelayApiResult.Success -> {
                try {
                    certificateStore.storePrivateKey(csrResult.privateKeyPem)
                    certificateStore.storeCertificate(response.data.cert)
                    certificateStore.storeSubdomain(response.data.subdomain)
                    OnboardingResult.Success(subdomain = response.data.subdomain)
                } catch (e: Exception) {
                    // Rollback on storage failure
                    certificateStore.clear()
                    OnboardingResult.StorageFailed(e)
                }
            }
            is RelayApiResult.RateLimited -> {
                OnboardingResult.RateLimited(retryAfterSeconds = response.retryAfterSeconds)
            }
            is RelayApiResult.Error -> {
                OnboardingResult.RelayError(
                    statusCode = response.statusCode,
                    message = response.message
                )
            }
            is RelayApiResult.NetworkError -> {
                OnboardingResult.NetworkError(cause = response.cause)
            }
        }
    }
}

sealed class OnboardingResult {
    data class Success(val subdomain: String) : OnboardingResult()

    data class RateLimited(val retryAfterSeconds: Long?) : OnboardingResult()

    data class RelayError(val statusCode: Int, val message: String) : OnboardingResult()

    data class NetworkError(val cause: Exception) : OnboardingResult()

    data class KeyGenerationFailed(val cause: Exception) : OnboardingResult()

    data class StorageFailed(val cause: Exception) : OnboardingResult()
}
