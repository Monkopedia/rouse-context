package com.rousecontext.tunnel

/**
 * Orchestrates first-run device onboarding with two-round-trip registration:
 *
 * Round 1: POST /register — get subdomain assignment
 * Round 2: POST /register/certs — submit CSR with correct FQDN, get both certs
 *
 * The device generates its own keypair. The private key never leaves the device.
 * The relay issues two certs using the device's public key:
 * - ACME server cert (serverAuth) for inner TLS with AI clients
 * - Relay CA client cert (clientAuth) for outer mTLS with relay
 *
 * Guarantees atomicity: if any step fails, no partial state is persisted.
 */
class OnboardingFlow(
    private val csrGenerator: CsrGenerator,
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore
) {

    /**
     * Executes the full onboarding flow.
     *
     * @param onRegistered Called after relay registration succeeds and before cert
     *   issuance begins, so callers can update progress UI.
     */
    suspend fun execute(
        firebaseToken: String,
        fcmToken: String,
        baseDomain: String = "rousecontext.com",
        onRegistered: (() -> Unit)? = null
    ): OnboardingResult {
        // Round 1: Register with relay to get subdomain
        val subdomain = when (
            val response = relayApiClient.register(
                firebaseToken = firebaseToken,
                fcmToken = fcmToken
            )
        ) {
            is RelayApiResult.Success -> response.data.subdomain
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

        // Generate keypair and CSR with the assigned FQDN
        val fqdn = "$subdomain.$baseDomain"
        val csrResult = try {
            csrGenerator.generate(fqdn)
        } catch (e: Exception) {
            return OnboardingResult.KeyGenerationFailed(e)
        }

        onRegistered?.invoke()

        // Round 2: Submit CSR to get both certs
        return when (
            val certResponse = relayApiClient.registerCerts(
                csrPem = csrResult.csrPem,
                firebaseToken = firebaseToken
            )
        ) {
            is RelayApiResult.Success -> {
                try {
                    certificateStore.storePrivateKey(csrResult.privateKeyPem)
                    certificateStore.storeCertificate(certResponse.data.serverCert)
                    certificateStore.storeClientCertificate(certResponse.data.clientCert)
                    certificateStore.storeRelayCaCert(certResponse.data.relayCaCert)
                    certificateStore.storeSubdomain(subdomain)
                    OnboardingResult.Success(subdomain = subdomain)
                } catch (e: Exception) {
                    certificateStore.clear()
                    OnboardingResult.StorageFailed(e)
                }
            }
            is RelayApiResult.RateLimited ->
                OnboardingResult.RateLimited(retryAfterSeconds = certResponse.retryAfterSeconds)
            is RelayApiResult.Error ->
                OnboardingResult.RelayError(
                    statusCode = certResponse.statusCode,
                    message = certResponse.message
                )
            is RelayApiResult.NetworkError ->
                OnboardingResult.NetworkError(cause = certResponse.cause)
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
