package com.rousecontext.tunnel

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
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
     * Register a new device with the relay server.
     * Sends the CSR, Firebase auth token, and FCM token.
     * Receives back a signed certificate, subdomain, and relay host.
     */
    suspend fun register(
        csrPem: String,
        firebaseToken: String,
        fcmToken: String
    ): RelayApiResult<RegisterResponse> = executeRequest {
        httpClient.post("$baseUrl/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    firebaseToken = firebaseToken,
                    csr = csrPem,
                    fcmToken = fcmToken
                )
            )
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
            else -> RelayApiResult.Error(
                statusCode = response.status.value,
                message = "Relay returned ${response.status}"
            )
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
    @SerialName("csr") val csr: String,
    @SerialName("fcm_token") val fcmToken: String
)

@Serializable
data class RegisterResponse(
    @SerialName("subdomain") val subdomain: String,
    @SerialName("cert") val cert: String,
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
data class RenewResponse(
    val certificatePem: String
)
