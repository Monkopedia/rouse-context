package com.rousecontext.mcp.core

import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Status of an authorization request, returned by [AuthorizationCodeManager.getStatus].
 */
sealed class AuthorizationRequestStatus {
    data object Pending : AuthorizationRequestStatus()
    data class Approved(val code: String, val state: String) : AuthorizationRequestStatus()
    data object Denied : AuthorizationRequestStatus()
    data object Expired : AuthorizationRequestStatus()
}

/**
 * The result of [AuthorizationCodeManager.createRequest], containing the server-generated
 * identifiers the caller needs.
 */
data class AuthorizationRequest(
    val requestId: String,
    val displayCode: String,
    val integration: String
)

/**
 * A pending authorization request for display in the phone approval UI.
 */
data class PendingAuthRequest(
    val displayCode: String,
    val integration: String,
    val clientId: String,
    val clientName: String?,
    val createdAt: Long
)

private const val AUTH_REQUEST_TTL_MS = 10L * 60 * 1000 // 10 minutes

/**
 * Characters allowed in display codes. Excludes 0, O, 1, I, L to avoid ambiguity.
 */
private const val DISPLAY_CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
private const val DISPLAY_CODE_HALF_LENGTH = 4

/**
 * Manages OAuth 2.1 authorization code flow with PKCE for per-integration auth.
 *
 * The flow:
 * 1. Client sends GET /authorize with code_challenge (PKCE S256)
 * 2. Server returns HTML page with a display code and polls for approval
 * 3. Phone user approves/denies using the display code
 * 4. On approval, the polling endpoint returns an authorization code
 * 5. Client exchanges the authorization code + code_verifier at /token
 * 6. Server validates PKCE and issues an access token
 *
 * Requests expire after 10 minutes.
 */
class AuthorizationCodeManager(
    private val tokenStore: TokenStore = InMemoryTokenStore(),
    private val clock: Clock = SystemClock
) {

    private data class PendingRequest(
        val requestId: String,
        val clientId: String,
        val codeChallenge: String,
        val codeChallengeMethod: String,
        val redirectUri: String,
        val state: String,
        val displayCode: String,
        val integration: String,
        val createdAt: Long,
        /** null = pending, true = approved, false = denied */
        var approved: Boolean? = null,
        /** Set when approved; the authorization code the client exchanges for a token. */
        var authorizationCode: String? = null
    )

    /**
     * Maps client_id to client_name, populated during /register.
     * Used to display human-readable names in the authorized clients list.
     */
    private val clientNames = ConcurrentHashMap<String, String>()

    private val requests = mutableListOf<PendingRequest>()

    /**
     * Registers a client_id to client_name mapping, called during /register.
     * The name is later used as the display label when issuing tokens.
     */
    fun registerClient(clientId: String, clientName: String) {
        clientNames[clientId] = clientName
    }

    /**
     * Creates a pending authorization request.
     * Returns the request ID and display code for the HTML page.
     */
    fun createRequest(
        clientId: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        redirectUri: String,
        state: String,
        integration: String
    ): AuthorizationRequest {
        val requestId = java.util.UUID.randomUUID().toString()
        val displayCode = generateDisplayCode()
        val now = clock.currentTimeMillis()

        synchronized(this) {
            cleanup(now)
            requests.add(
                PendingRequest(
                    requestId = requestId,
                    clientId = clientId,
                    codeChallenge = codeChallenge,
                    codeChallengeMethod = codeChallengeMethod,
                    redirectUri = redirectUri,
                    state = state,
                    displayCode = displayCode,
                    integration = integration,
                    createdAt = now
                )
            )
        }

        return AuthorizationRequest(
            requestId = requestId,
            displayCode = displayCode,
            integration = integration
        )
    }

    /**
     * Returns the current status of an authorization request.
     * Returns [AuthorizationRequestStatus.Expired] for unknown or expired requests.
     */
    fun getStatus(requestId: String): AuthorizationRequestStatus {
        synchronized(this) {
            cleanup(clock.currentTimeMillis())
            val request = requests.find { it.requestId == requestId }
                ?: return AuthorizationRequestStatus.Expired

            val elapsed = clock.currentTimeMillis() - request.createdAt
            if (elapsed > AUTH_REQUEST_TTL_MS) {
                requests.remove(request)
                return AuthorizationRequestStatus.Expired
            }

            return when (request.approved) {
                null -> AuthorizationRequestStatus.Pending
                true -> AuthorizationRequestStatus.Approved(
                    code = request.authorizationCode!!,
                    state = request.state
                )
                false -> AuthorizationRequestStatus.Denied
            }
        }
    }

    /**
     * Approves the authorization request identified by the given display code.
     * Called from the phone UI when the user taps Approve.
     * Returns true if a matching pending request was found and approved.
     */
    fun approve(displayCode: String): Boolean {
        synchronized(this) {
            val request = requests.find {
                it.displayCode == displayCode && it.approved == null
            } ?: return false

            val elapsed = clock.currentTimeMillis() - request.createdAt
            if (elapsed > AUTH_REQUEST_TTL_MS) {
                requests.remove(request)
                return false
            }

            request.approved = true
            request.authorizationCode = generateAuthorizationCode()
            return true
        }
    }

    /**
     * Denies the authorization request identified by the given display code.
     * Called from the phone UI when the user taps Deny.
     * Returns true if a matching pending request was found and denied.
     */
    fun deny(displayCode: String): Boolean {
        synchronized(this) {
            val request = requests.find {
                it.displayCode == displayCode && it.approved == null
            } ?: return false

            request.approved = false
            return true
        }
    }

    /**
     * Exchanges an authorization code for an access token.
     * Validates the PKCE code_verifier against the stored code_challenge.
     * Returns the access token on success, null on failure.
     * The authorization code is single-use and is consumed by this call.
     */
    fun exchangeCode(authCode: String, codeVerifier: String): String? {
        synchronized(this) {
            val request = requests.find {
                it.authorizationCode == authCode && it.approved == true
            } ?: return null

            // Consume the code (single-use)
            requests.remove(request)

            // Validate PKCE
            if (!validatePkce(codeVerifier, request.codeChallenge)) {
                return null
            }

            val clientName = clientNames[request.clientId]
            return tokenStore.createToken(request.integration, request.clientId, clientName)
        }
    }

    /**
     * Returns the list of pending authorization requests for display in the phone approval UI.
     */
    fun pendingRequests(): List<PendingAuthRequest> {
        synchronized(this) {
            val now = clock.currentTimeMillis()
            return requests
                .filter { it.approved == null && (now - it.createdAt) <= AUTH_REQUEST_TTL_MS }
                .map {
                    PendingAuthRequest(
                        displayCode = it.displayCode,
                        integration = it.integration,
                        clientId = it.clientId,
                        clientName = clientNames[it.clientId],
                        createdAt = it.createdAt
                    )
                }
        }
    }

    /**
     * Removes expired pending requests. Must be called inside synchronized(this).
     */
    private fun cleanup(now: Long) {
        requests.removeAll { (now - it.createdAt) > AUTH_REQUEST_TTL_MS }
    }

    private fun generateDisplayCode(): String {
        val first = (1..DISPLAY_CODE_HALF_LENGTH)
            .map { DISPLAY_CODE_CHARS[Random.nextInt(DISPLAY_CODE_CHARS.length)] }
            .toCharArray()
            .concatToString()
        val second = (1..DISPLAY_CODE_HALF_LENGTH)
            .map { DISPLAY_CODE_CHARS[Random.nextInt(DISPLAY_CODE_CHARS.length)] }
            .toCharArray()
            .concatToString()
        return "$first-$second"
    }

    private fun generateAuthorizationCode(): String {
        return Random.nextBytes(32).encodeBase64Url()
    }
}

/**
 * Validates PKCE S256: BASE64URL(SHA256(code_verifier)) must equal code_challenge.
 */
internal fun validatePkce(codeVerifier: String, codeChallenge: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
    val computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    return computed == codeChallenge
}
