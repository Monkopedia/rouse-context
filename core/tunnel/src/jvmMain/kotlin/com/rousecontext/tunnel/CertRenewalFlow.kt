package com.rousecontext.tunnel

import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import javax.naming.ldap.LdapName
import kotlinx.coroutines.delay

/**
 * Credentials used on the Firebase-signature renewal path for an expired cert.
 *
 * - [token]: a fresh Firebase ID token (JWT).
 * - [signature]: Base64-encoded SHA256withECDSA signature over the raw DER bytes of the
 *   *current* renewal CSR, produced with the device private key from the Android Keystore.
 */
data class FirebaseRenewalCredentials(val token: String, val signature: String)

/**
 * Supplies Firebase credentials for the expired-cert renewal path. Invoked by
 * [CertRenewalFlow.renewWithFirebase] *after* the renewal CSR has been generated so the
 * signature covers the exact CSR bytes the relay will receive. Returns `null` when a Firebase
 * user is not signed in or the signing operation fails; callers treat that as a transient
 * condition and retry later.
 */
fun interface FirebaseRenewalCredentialsProvider {
    suspend fun obtain(csrDer: ByteArray): FirebaseRenewalCredentials?
}

/**
 * Signs the renewal CSR DER bytes with the device's registered private key for the
 * valid-cert (mTLS-equivalent) renewal path. Invoked by [CertRenewalFlow.renewWithMtls]
 * after the CSR is generated. Returns `null` when signing fails (e.g. Keystore locked or
 * key unavailable); callers treat that as a transient condition and retry later.
 */
fun interface CsrSigner {
    suspend fun signCsr(csrDer: ByteArray): String?
}

/**
 * Supplies keypair credentials for the expired-cert renewal path (`foss`
 * flavor, issue #462). Invoked by [CertRenewalFlow.renewExpired] after the
 * renewal CSR is generated so both the CSR signature and the renewal proof
 * cover the exact CSR bytes the relay will receive. Returns `null` when the
 * device is not on the keypair flavor (Firebase fallback) or signing fails.
 */
fun interface KeypairRenewalCredentialsProvider {
    suspend fun obtain(csrDer: ByteArray): KeypairRenewalCredentials?
}

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
    private val deviceKeyManager: DeviceKeyManager,
    private val certInspector: CertInspector = CertInspector(),
    private val maxRetries: Int = 3,
    private val baseRetryDelayMs: Long = 1000L,
    private val defaultBaseDomain: String = "rousecontext.com"
) {

    /**
     * Renew the device certificate while the current cert is still valid ("mTLS-equivalent").
     *
     * Retained for backwards-compatibility with callers that already have a CSR signature in
     * hand. Production callers should prefer the [CsrSigner] overload below so the signature
     * is computed over the exact CSR DER bytes the relay will receive.
     */
    suspend fun renewWithMtls(
        signature: String,
        baseDomain: String = defaultBaseDomain
    ): RenewalResult = renewWithMtls(
        csrSigner = { signature },
        baseDomain = baseDomain
    )

    /**
     * Renew the device certificate while the current cert is still valid ("mTLS-equivalent").
     * The [csrSigner] receives the raw DER bytes of the freshly-generated renewal CSR and
     * returns a Base64 SHA256withECDSA signature over those bytes produced with the device's
     * registered private key. If the signer returns `null`, the renewal is reported as
     * [RenewalResult.FirebaseAuthUnavailable] (transient Keystore-signing failure) so the
     * caller retries later.
     */
    @Suppress("ReturnCount")
    suspend fun renewWithMtls(
        csrSigner: CsrSigner,
        baseDomain: String = defaultBaseDomain
    ): RenewalResult {
        val currentCert = certificateStore.getCertificate()
        val subdomain = certificateStore.getSubdomain()
        if (currentCert == null || subdomain == null) return RenewalResult.NoCertificate

        if (certInspector.inspect(currentCert).isExpired) return RenewalResult.CertExpired

        val csrResult = try {
            val keyPair = deviceKeyManager.getOrCreateKeyPair()
            csrGenerator.generate("*.$subdomain.$baseDomain", keyPair)
        } catch (e: Exception) {
            return RenewalResult.KeyGenerationFailed(e)
        }

        val signature = csrSigner.signCsr(csrResult.csrDer)
            ?: return RenewalResult.FirebaseAuthUnavailable

        return executeWithRetry {
            val result = relayApiClient.renewWithMtls(
                csrPem = csrResult.csrPem,
                subdomain = subdomain,
                signature = signature
            )
            handleRenewResponse(result, currentCert)
        }
    }

    /**
     * Renew the device certificate using Firebase token + signature (cert expired).
     *
     * Retained for backwards-compatibility with callers that already have credentials in hand.
     * Production callers should prefer the [FirebaseRenewalCredentialsProvider] overload below
     * so the signature is computed over the exact CSR DER bytes generated here.
     */
    suspend fun renewWithFirebase(
        firebaseToken: String,
        signature: String,
        baseDomain: String = defaultBaseDomain
    ): RenewalResult = renewWithFirebase(
        credentialsProvider = { FirebaseRenewalCredentials(firebaseToken, signature) },
        baseDomain = baseDomain
    )

    /**
     * Renew the device certificate using Firebase token + signature. The [credentialsProvider]
     * receives the raw DER bytes of the freshly-generated renewal CSR and returns a Firebase
     * ID token plus a SHA256withECDSA signature over those bytes. If the provider returns
     * `null`, the renewal is reported as a [RenewalResult.NetworkError] surrogate so the
     * caller retries later (Firebase token / Keystore signing failures are transient).
     */
    @Suppress("ReturnCount")
    suspend fun renewWithFirebase(
        credentialsProvider: FirebaseRenewalCredentialsProvider,
        baseDomain: String = defaultBaseDomain
    ): RenewalResult {
        val subdomain = certificateStore.getSubdomain()
            ?: return RenewalResult.NoCertificate
        // The current (typically expired) cert still carries a valid subject CN, so pass it
        // into validation: the CN-mismatch safety net must fire on the expired-cert path too.
        val currentCert = certificateStore.getCertificate()

        val csrResult = try {
            val keyPair = deviceKeyManager.getOrCreateKeyPair()
            csrGenerator.generate("*.$subdomain.$baseDomain", keyPair)
        } catch (e: Exception) {
            return RenewalResult.KeyGenerationFailed(e)
        }

        val credentials = credentialsProvider.obtain(csrResult.csrDer)
            ?: return RenewalResult.FirebaseAuthUnavailable

        return executeWithRetry {
            val result = relayApiClient.renewWithFirebase(
                csrPem = csrResult.csrPem,
                subdomain = subdomain,
                firebaseToken = credentials.token,
                signature = credentials.signature
            )
            handleRenewResponse(result, currentCert)
        }
    }

    /**
     * Renew an expired certificate, preferring the keypair path (`foss`) and
     * falling back to Firebase (`google`). The keypair provider is consulted
     * first; if it yields credentials the renewal uses the keypair proof,
     * otherwise the Firebase provider is consulted. If neither yields
     * credentials the result is [RenewalResult.FirebaseAuthUnavailable] so the
     * worker retries later. Issue #462.
     */
    @Suppress("ReturnCount")
    suspend fun renewExpired(
        keypairProvider: KeypairRenewalCredentialsProvider,
        firebaseProvider: FirebaseRenewalCredentialsProvider,
        baseDomain: String = defaultBaseDomain
    ): RenewalResult {
        val subdomain = certificateStore.getSubdomain()
            ?: return RenewalResult.NoCertificate
        // The current (expired) cert still carries a valid subject CN; pass it into validation
        // so the CN-mismatch safety net runs on both the keypair and Firebase fallback branches.
        val currentCert = certificateStore.getCertificate()

        val csrResult = try {
            val keyPair = deviceKeyManager.getOrCreateKeyPair()
            csrGenerator.generate("*.$subdomain.$baseDomain", keyPair)
        } catch (e: Exception) {
            return RenewalResult.KeyGenerationFailed(e)
        }

        keypairProvider.obtain(csrResult.csrDer)?.let { keypair ->
            return executeWithRetry {
                val result = relayApiClient.renewWithKeypair(
                    csrPem = csrResult.csrPem,
                    subdomain = subdomain,
                    csrSignature = keypair.csrSignature,
                    proof = keypair.proof
                )
                handleRenewResponse(result, currentCert)
            }
        }

        val credentials = firebaseProvider.obtain(csrResult.csrDer)
            ?: return RenewalResult.FirebaseAuthUnavailable

        return executeWithRetry {
            val result = relayApiClient.renewWithFirebase(
                csrPem = csrResult.csrPem,
                subdomain = subdomain,
                firebaseToken = credentials.token,
                signature = credentials.signature
            )
            handleRenewResponse(result, currentCert)
        }
    }

    private suspend fun handleRenewResponse(
        result: RelayApiResult<RenewResponse>,
        currentCertForValidation: String?
    ): RetryableResult {
        if (result is RelayApiResult.Success && currentCertForValidation != null) {
            val oldInspection = certInspector.inspect(currentCertForValidation)
            val newInspection = certInspector.inspect(result.data.serverCert)
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
                certificateStore.storeCertificate(result.data.serverCert)
                certificateStore.storeClientCertificate(result.data.clientCert)
                certificateStore.storeRelayCaCert(result.data.relayCaCert)
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

    /**
     * Firebase auth / signing path was unavailable (no signed-in user, or Keystore signing
     * threw). Transient — the worker retries on the next scheduled run.
     */
    data object FirebaseAuthUnavailable : RenewalResult()

    data class RateLimited(val retryAfterSeconds: Long?) : RenewalResult()

    data class RelayError(val statusCode: Int, val message: String) : RenewalResult()

    data class NetworkError(val cause: Exception) : RenewalResult()

    data class KeyGenerationFailed(val cause: Exception) : RenewalResult()

    data class CnMismatch(val expected: String?, val actual: String?) : RenewalResult()
}

/**
 * Inspects PEM certificate properties (subject CN and expiry). `open` so tests can inject
 * deterministic fakes.
 *
 * The default implementation parses the PEM with [CertificateFactory] into an
 * [X509Certificate]. A malformed or unparseable input degrades safely to an empty [CertInfo]
 * (null CN, not expired) rather than throwing inside the renewal flow — a parse failure must
 * not abort a renewal nor be mistaken for an expiry/CN-mismatch signal.
 */
open class CertInspector {
    open fun inspect(pemCertificate: String): CertInfo = try {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(
                ByteArrayInputStream(pemCertificate.toByteArray(Charsets.UTF_8))
            ) as X509Certificate
        CertInfo(
            commonName = extractCommonName(cert),
            isExpired = cert.notAfter.toInstant().isBefore(Instant.now())
        )
    } catch (_: Exception) {
        CertInfo()
    }

    private fun extractCommonName(cert: X509Certificate): String? = runCatching {
        LdapName(cert.subjectX500Principal.name).rdns
            .firstOrNull { it.type.equals("CN", ignoreCase = true) }
            ?.value
            ?.toString()
    }.getOrNull()
}

data class CertInfo(val commonName: String? = null, val isExpired: Boolean = false)
