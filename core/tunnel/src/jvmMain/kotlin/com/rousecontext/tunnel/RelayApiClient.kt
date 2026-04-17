package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * HTTP client for the relay server's registration and renewal endpoints.
 */
class RelayApiClient(
    private val baseUrl: String,
    private val httpClient: HttpClient = createDefaultClient()
) {

    /**
     * Reserve a subdomain (single-word-preferred, per issue #92) before
     * registering. The relay persists a short-TTL reservation keyed by the
     * Firebase UID extracted from [firebaseToken]. A subsequent call to
     * [register] by the same UID consumes that reservation and adopts the
     * reserved subdomain.
     *
     * The client should call this immediately before [register] during
     * onboarding. The reservation TTL is short (minutes) because the
     * relay guarantees single-use semantics: [register] deletes the
     * reservation as soon as it consumes it.
     */
    suspend fun requestSubdomain(firebaseToken: String): RelayApiResult<RequestSubdomainResponse> =
        executeRequest {
            httpClient.post("$baseUrl/request-subdomain") {
                contentType(ContentType.Application.Json)
                setBody(RequestSubdomainRequest(firebaseToken = firebaseToken))
            }
        }

    /**
     * Round 1: Register a new device with the relay server.
     * Sends Firebase auth token, FCM token, and the list of integration IDs
     * the device wants secrets provisioned for. The relay generates one
     * `{adjective}-{integrationId}` secret per entry and returns the mapping
     * in [RegisterResponse.secrets].
     */
    suspend fun register(
        firebaseToken: String,
        fcmToken: String,
        integrationIds: List<String> = emptyList()
    ): RelayApiResult<RegisterResponse> = executeRequest {
        httpClient.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    firebaseToken = firebaseToken,
                    fcmToken = fcmToken,
                    integrations = integrationIds
                )
            )
        }
    }

    /**
     * Round 2: Submit CSR to receive both certificates.
     * The CSR must have CN={subdomain}.rousecontext.com.
     * Returns server cert (ACME/LE), client cert (relay CA), and relay CA cert.
     *
     * [signature] is a base64-encoded DER ECDSA signature over the raw CSR DER
     * bytes using the device's registered private key. It is required when the
     * device already has a public key on file from a prior /register/certs
     * round (proof-of-possession, see issue #201). It must be omitted on the
     * initial round-2 call for a fresh registration, where the relay binds
     * the public key for the first time.
     */
    suspend fun registerCerts(
        csrPem: String,
        firebaseToken: String,
        signature: String? = null
    ): RelayApiResult<CertResponse> = executeRequest {
        httpClient.post("$baseUrl/register/certs") {
            contentType(ContentType.Application.Json)
            setBody(
                CertRequest(
                    firebaseToken = firebaseToken,
                    csr = csrToBase64(csrPem),
                    signature = signature
                )
            )
        }
    }

    /**
     * Rotate the device's integration secrets. Authenticated via mTLS:
     * the subdomain is extracted from the client certificate on the relay
     * side and the body no longer carries it. See issue #202 — accepting a
     * subdomain from the request body turned this endpoint into an
     * unauthenticated DoS.
     *
     * [subdomain] is retained only for local call-site logging; it is not
     * transmitted in the request body.
     */
    suspend fun updateSecrets(
        subdomain: String,
        integrationIds: List<String>
    ): RelayApiResult<UpdateSecretsResponse> = executeRequest {
        // subdomain is intentionally ignored for transport — the relay uses
        // the mTLS client cert CN. Passed in for caller logging only.
        @Suppress("UNUSED_VARIABLE")
        val localSubdomain = subdomain
        httpClient.post("$baseUrl/rotate-secret") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSecretsRequest(integrations = integrationIds))
        }
    }

    /**
     * Renew a device certificate while the current cert is still valid ("mTLS-equivalent"
     * path): the device signs the CSR DER bytes with its registered private key. The
     * relay verifies that signature against the public key it already has on file.
     * No Firebase token is required on this path.
     */
    suspend fun renewWithMtls(
        csrPem: String,
        subdomain: String,
        signature: String
    ): RelayApiResult<RenewResponse> = executeRequest {
        httpClient.post("$baseUrl/renew") {
            contentType(ContentType.Application.Json)
            setBody(
                RenewRequest(
                    csr = csrToBase64(csrPem),
                    subdomain = subdomain,
                    signature = signature
                )
            )
        }
    }

    /**
     * Renew a device certificate using Firebase token + signature (for expired certs).
     * Firebase re-authenticates the user; the signature still proves control of the
     * registered private key.
     */
    suspend fun renewWithFirebase(
        csrPem: String,
        subdomain: String,
        firebaseToken: String,
        signature: String
    ): RelayApiResult<RenewResponse> = executeRequest {
        httpClient.post("$baseUrl/renew") {
            contentType(ContentType.Application.Json)
            setBody(
                RenewRequest(
                    csr = csrToBase64(csrPem),
                    subdomain = subdomain,
                    firebaseToken = firebaseToken,
                    signature = signature
                )
            )
        }
    }

    private fun csrToBase64(csrPem: String): String = csrPem
        .replace("-----BEGIN CERTIFICATE REQUEST-----", "")
        .replace("-----END CERTIFICATE REQUEST-----", "")
        .replace("-----BEGIN NEW CERTIFICATE REQUEST-----", "")
        .replace("-----END NEW CERTIFICATE REQUEST-----", "")
        .replace("\n", "")
        .replace("\r", "")
        .trim()

    private suspend inline fun <reified T> executeRequest(
        crossinline block: suspend () -> HttpResponse
    ): RelayApiResult<T> = try {
        val response = block()
        when (response.status.value) {
            200, 201 -> RelayApiResult.Success(response.body<T>())
            429 -> {
                val retryAfter = response.headers["Retry-After"]?.toLongOrNull()
                RelayApiResult.RateLimited(retryAfterSeconds = retryAfter)
            }
            else -> {
                val body = try {
                    response.bodyAsText()
                } catch (_: Exception) {
                    ""
                }
                RelayApiResult.Error(
                    statusCode = response.status.value,
                    message = "Relay returned ${response.status}: $body"
                )
            }
        }
    } catch (e: Exception) {
        RelayApiResult.NetworkError(cause = e)
    }

    companion object {
        fun createDefaultClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        encodeDefaults = true
                    }
                )
            }
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
        }
    }
}

sealed class RelayApiResult<out T> {
    data class Success<T>(val data: T) : RelayApiResult<T>()

    data class RateLimited(val retryAfterSeconds: Long?) : RelayApiResult<Nothing>()

    data class Error(val statusCode: Int, val message: String) : RelayApiResult<Nothing>()

    data class NetworkError(val cause: Exception) : RelayApiResult<Nothing>()
}

@Serializable
data class RequestSubdomainRequest(@SerialName("firebase_token") val firebaseToken: String)

@Serializable
data class RequestSubdomainResponse(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("base_domain") val baseDomain: String,
    @SerialName("fqdn") val fqdn: String,
    @SerialName("reservation_ttl_seconds") val reservationTtlSeconds: Long
)

@Serializable
data class RegisterRequest(
    @SerialName("firebase_token") val firebaseToken: String,
    @SerialName("fcm_token") val fcmToken: String,
    @SerialName("integrations") val integrations: List<String> = emptyList()
)

@Serializable
data class RegisterResponse(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("relay_host") val relayHost: String,
    @SerialName("secrets") val secrets: Map<String, String> = emptyMap()
)

@Serializable
data class UpdateSecretsRequest(@SerialName("integrations") val integrations: List<String>)

@Serializable
data class UpdateSecretsResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("secrets") val secrets: Map<String, String> = emptyMap()
)

@Serializable
data class CertRequest(
    @SerialName("firebase_token") val firebaseToken: String,
    @SerialName("csr") val csr: String,
    @SerialName("signature") val signature: String? = null
)

@Serializable
data class CertResponse(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("server_cert") val serverCert: String,
    @SerialName("client_cert") val clientCert: String,
    @SerialName("relay_ca_cert") val relayCaCert: String,
    @SerialName("relay_host") val relayHost: String
)

@Serializable
data class RenewRequest(
    @SerialName("csr") val csr: String,
    @SerialName("subdomain") val subdomain: String,
    @SerialName("firebase_token") val firebaseToken: String? = null,
    @SerialName("signature") val signature: String? = null
)

@Serializable
data class RenewResponse(
    @SerialName("server_cert") val serverCert: String,
    @SerialName("client_cert") val clientCert: String,
    @SerialName("relay_ca_cert") val relayCaCert: String
)
