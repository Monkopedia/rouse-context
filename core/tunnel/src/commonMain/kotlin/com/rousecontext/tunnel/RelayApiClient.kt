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
     * Round 1: Register a new device with the relay server.
     * Sends Firebase auth token and FCM token.
     * Receives back the assigned subdomain.
     */
    suspend fun register(
        firebaseToken: String,
        fcmToken: String,
        validSecrets: List<String> = emptyList()
    ): RelayApiResult<RegisterResponse> = executeRequest {
        httpClient.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    firebaseToken = firebaseToken,
                    fcmToken = fcmToken,
                    validSecrets = validSecrets
                )
            )
        }
    }

    /**
     * Round 2: Submit CSR to receive both certificates.
     * The CSR must have CN={subdomain}.rousecontext.com.
     * Returns server cert (ACME/LE), client cert (relay CA), and relay CA cert.
     */
    suspend fun registerCerts(csrPem: String, firebaseToken: String): RelayApiResult<CertResponse> =
        executeRequest {
            httpClient.post("$baseUrl/register/certs") {
                contentType(ContentType.Application.Json)
                val csrBase64 = csrPem
                    .replace("-----BEGIN CERTIFICATE REQUEST-----", "")
                    .replace("-----END CERTIFICATE REQUEST-----", "")
                    .replace("-----BEGIN NEW CERTIFICATE REQUEST-----", "")
                    .replace("-----END NEW CERTIFICATE REQUEST-----", "")
                    .replace("\n", "")
                    .replace("\r", "")
                    .trim()
                setBody(
                    CertRequest(
                        firebaseToken = firebaseToken,
                        csr = csrBase64
                    )
                )
            }
        }

    /**
     * Update the device's valid secrets. Authenticated via mTLS.
     * The client generates secrets locally and sends the full list to the relay.
     */
    suspend fun updateSecrets(
        subdomain: String,
        validSecrets: List<String>
    ): RelayApiResult<UpdateSecretsResponse> = executeRequest {
        httpClient.post("$baseUrl/rotate-secret") {
            contentType(ContentType.Application.Json)
            setBody(UpdateSecretsRequest(subdomain = subdomain, validSecrets = validSecrets))
        }
    }

    /**
     * Renew a device certificate using mTLS (valid cert) authentication.
     */
    suspend fun renewWithMtls(
        csrPem: String,
        currentCertPem: String
    ): RelayApiResult<RenewResponse> = executeRequest {
        httpClient.post("$baseUrl/renew") {
            contentType(ContentType.Application.Json)
            setBody(
                RenewRequest(
                    csrPem = csrPem,
                    authMethod = "mtls",
                    currentCertPem = currentCertPem
                )
            )
        }
    }

    /**
     * Renew a device certificate using Firebase token + signature (for expired certs).
     */
    suspend fun renewWithFirebase(
        csrPem: String,
        firebaseToken: String,
        signature: String
    ): RelayApiResult<RenewResponse> = executeRequest {
        httpClient.post("$baseUrl/renew") {
            contentType(ContentType.Application.Json)
            setBody(
                RenewRequest(
                    csrPem = csrPem,
                    authMethod = "firebase",
                    firebaseToken = firebaseToken,
                    signature = signature
                )
            )
        }
    }

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
data class RegisterRequest(
    @SerialName("firebase_token") val firebaseToken: String,
    @SerialName("fcm_token") val fcmToken: String,
    @SerialName("valid_secrets") val validSecrets: List<String> = emptyList()
)

@Serializable
data class RegisterResponse(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("relay_host") val relayHost: String
)

@Serializable
data class UpdateSecretsRequest(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("valid_secrets") val validSecrets: List<String>
)

@Serializable
data class UpdateSecretsResponse(@SerialName("status") val status: String = "ok")

@Serializable
data class CertRequest(
    @SerialName("firebase_token") val firebaseToken: String,
    @SerialName("csr") val csr: String
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
    val csrPem: String,
    val authMethod: String,
    val currentCertPem: String? = null,
    val firebaseToken: String? = null,
    val signature: String? = null
)

@Serializable
data class RenewResponse(val certificatePem: String)
