package com.rousecontext.tunnel

/**
 * Provisions ACME server + relay CA client certificates for a device that has
 * already completed onboarding (has a subdomain assigned).
 *
 * This is triggered when the user enables their first integration -- not during
 * onboarding -- to avoid burning ACME certs for devices that never serve anything.
 *
 * Idempotent: if certificates already exist, returns [CertProvisioningResult.AlreadyProvisioned].
 */
class CertProvisioningFlow(
    private val csrGenerator: CsrGenerator,
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore,
    private val defaultBaseDomain: String = "rousecontext.com"
) {

    /**
     * Provisions certificates if not already present.
     *
     * Requires that [CertificateStore.getSubdomain] is non-null (device is onboarded).
     *
     * @param firebaseToken Fresh Firebase ID token for relay authentication.
     * @param baseDomain The base domain for FQDN construction. Defaults to the value
     *   passed to the constructor (which callers typically wire from BuildConfig).
     */
    suspend fun execute(
        firebaseToken: String,
        baseDomain: String = defaultBaseDomain
    ): CertProvisioningResult {
        // Already provisioned?
        if (certificateStore.getCertificate() != null &&
            certificateStore.getClientCertificate() != null
        ) {
            return CertProvisioningResult.AlreadyProvisioned
        }

        val subdomain = certificateStore.getSubdomain()
            ?: return CertProvisioningResult.NotOnboarded

        // Generate keypair and CSR with wildcard FQDN to match ACME order
        val fqdn = "*.$subdomain.$baseDomain"
        val csrResult = try {
            csrGenerator.generate(fqdn)
        } catch (e: Exception) {
            return CertProvisioningResult.KeyGenerationFailed(e)
        }

        // Submit CSR to get both certs
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
                    CertProvisioningResult.Success
                } catch (e: Exception) {
                    // Narrow rollback: only clear cert-related state. The onboarding
                    // state (subdomain, integration secrets) is set earlier by
                    // OnboardingFlow and must survive a cert-provisioning failure,
                    // otherwise the user is bounced back to the Welcome screen on
                    // next launch even though registration completed (issue #163).
                    certificateStore.clearCertificates()
                    CertProvisioningResult.StorageFailed(e)
                }
            }
            is RelayApiResult.RateLimited ->
                CertProvisioningResult.RateLimited(
                    retryAfterSeconds = certResponse.retryAfterSeconds
                )
            is RelayApiResult.Error ->
                CertProvisioningResult.RelayError(
                    statusCode = certResponse.statusCode,
                    message = certResponse.message
                )
            is RelayApiResult.NetworkError ->
                CertProvisioningResult.NetworkError(cause = certResponse.cause)
        }
    }
}

sealed class CertProvisioningResult {
    data object Success : CertProvisioningResult()

    data object AlreadyProvisioned : CertProvisioningResult()

    data object NotOnboarded : CertProvisioningResult()

    data class RateLimited(val retryAfterSeconds: Long?) : CertProvisioningResult()

    data class RelayError(val statusCode: Int, val message: String) : CertProvisioningResult()

    data class NetworkError(val cause: Exception) : CertProvisioningResult()

    data class KeyGenerationFailed(val cause: Exception) : CertProvisioningResult()

    data class StorageFailed(val cause: Exception) : CertProvisioningResult()
}
