package com.rousecontext.tunnel

import kotlinx.coroutines.delay

/**
 * Orchestrates certificate renewal. Two authentication paths:
 * - Valid (non-expired) cert: mTLS /renew
 * - Expired cert: Firebase token + signature /renew
 *
 * Handles rate limiting with scheduled retry and network failures with exponential backoff.
 */
class CertRenewalFlow(
    private val csrGenerator: CsrGenerator,
    private val relayApiClient: RelayApiClient,
    private val certificateStore: CertificateStore,
    private val certInspector: CertInspector = CertInspector(),
    private val maxRetries: Int = 3,
    private val baseRetryDelayMs: Long = 1000L
) {

    /**
     * Renew the device certificate using mTLS authentication (cert still valid).
     */
    suspend fun renewWithMtls(baseDomain: String = "rousecontext.com"): RenewalResult {
        val currentCert = certificateStore.getCertificate()
            ?: return RenewalResult.NoCertificate
        val subdomain = certificateStore.getSubdomain()
            ?: return RenewalResult.NoCertificate

        val inspection = certInspector.inspect(currentCert)
        if (inspection.isExpired) {
            return RenewalResult.CertExpired
        }

        val csrResult = try {
            csrGenerator.generate("*.$subdomain.$baseDomain")
        } catch (e: Exception) {
            return RenewalResult.KeyGenerationFailed(e)
        }

        return executeWithRetry {
            val result = relayApiClient.renewWithMtls(
                csrPem = csrResult.csrPem,
                currentCertPem = currentCert
            )
            handleRenewResponse(result, csrResult, currentCert)
        }
    }

    /**
     * Renew the device certificate using Firebase token + signature (cert expired).
     */
    suspend fun renewWithFirebase(
        firebaseToken: String,
        signature: String,
        baseDomain: String = "rousecontext.com"
    ): RenewalResult {
        val subdomain = certificateStore.getSubdomain()
            ?: return RenewalResult.NoCertificate

        val csrResult = try {
            csrGenerator.generate("*.$subdomain.$baseDomain")
        } catch (e: Exception) {
            return RenewalResult.KeyGenerationFailed(e)
        }

        return executeWithRetry {
            val result = relayApiClient.renewWithFirebase(
                csrPem = csrResult.csrPem,
                firebaseToken = firebaseToken,
                signature = signature
            )
            handleRenewResponse(result, csrResult, null)
        }
    }

    private suspend fun handleRenewResponse(
        result: RelayApiResult<RenewResponse>,
        csrResult: CsrResult,
        currentCertForValidation: String?
    ): RetryableResult {
        if (result is RelayApiResult.Success && currentCertForValidation != null) {
            val oldInspection = certInspector.inspect(currentCertForValidation)
            val newInspection = certInspector.inspect(result.data.certificatePem)
            if (oldInspection.commonName != newInspection.commonName) {
                return RetryableResult.Terminal(
                    RenewalResult.CnMismatch(
                        expected = oldInspection.commonName,
                        actual = newInspection.commonName
                    )
                )
            }
        }
        return when (result) {
            is RelayApiResult.Success -> {
                certificateStore.storePrivateKey(csrResult.privateKeyPem)
                certificateStore.storeCertificate(result.data.certificatePem)
                RetryableResult.Terminal(RenewalResult.Success)
            }
            is RelayApiResult.RateLimited -> {
                RetryableResult.Terminal(
                    RenewalResult.RateLimited(retryAfterSeconds = result.retryAfterSeconds)
                )
            }
            is RelayApiResult.Error -> {
                RetryableResult.Terminal(
                    RenewalResult.RelayError(
                        statusCode = result.statusCode,
                        message = result.message
                    )
                )
            }
            is RelayApiResult.NetworkError -> {
                RetryableResult.Retryable(result.cause)
            }
        }
    }

    private suspend fun executeWithRetry(block: suspend () -> RetryableResult): RenewalResult {
        var lastException: Exception? = null
        for (attempt in 0 until maxRetries) {
            when (val result = block()) {
                is RetryableResult.Terminal -> return result.result
                is RetryableResult.Retryable -> {
                    lastException = result.cause
                    if (attempt < maxRetries - 1) {
                        delay(baseRetryDelayMs * (1L shl attempt))
                    }
                }
            }
        }
        return RenewalResult.NetworkError(
            cause = lastException ?: IllegalStateException("Retries exhausted")
        )
    }
}

private sealed class RetryableResult {
    data class Terminal(val result: RenewalResult) : RetryableResult()

    data class Retryable(val cause: Exception) : RetryableResult()
}

sealed class RenewalResult {
    data object Success : RenewalResult()

    data object NoCertificate : RenewalResult()

    data object CertExpired : RenewalResult()

    data class RateLimited(val retryAfterSeconds: Long?) : RenewalResult()

    data class RelayError(val statusCode: Int, val message: String) : RenewalResult()

    data class NetworkError(val cause: Exception) : RenewalResult()

    data class KeyGenerationFailed(val cause: Exception) : RenewalResult()

    data class CnMismatch(val expected: String?, val actual: String?) : RenewalResult()
}

/**
 * Inspects PEM certificate properties. Expect/actual or injectable for testing.
 */
open class CertInspector {
    open fun inspect(pemCertificate: String): CertInfo = CertInfo()
}

data class CertInfo(
    val commonName: String? = null,
    val isExpired: Boolean = false
)
