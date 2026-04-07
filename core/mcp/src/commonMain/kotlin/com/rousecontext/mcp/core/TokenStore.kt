package com.rousecontext.mcp.core

/**
 * Token verification and management, scoped per integration.
 *
 * The app implements this backed by Room. Tokens are opaque strings (32 bytes base64url),
 * stored as hashes. Each token is scoped to a single integration - a token issued for
 * `/health` cannot access `/notifications`.
 */
interface TokenStore {

    /**
     * Validates that [token] is a currently active, non-expired access token for
     * [integrationId]. Returns false for revoked tokens, expired tokens, tokens
     * belonging to other integrations, or unknown tokens.
     */
    fun validateToken(integrationId: String, token: String): Boolean

    /**
     * Creates a new access token + refresh token pair for [integrationId], associated
     * with the given [clientId]. The optional [clientName] is a human-readable label
     * for display in the authorized clients UI.
     */
    fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String? = null
    ): TokenPair

    /**
     * Revokes a specific access token. No-op if the token does not exist.
     */
    fun revokeToken(integrationId: String, token: String)

    /**
     * Exchanges a valid refresh token for a new token pair (access + refresh).
     * The old refresh token is invalidated (rotation). Returns null if the
     * refresh token is invalid, expired, or belongs to a different integration.
     */
    fun refreshToken(integrationId: String, refreshToken: String): TokenPair?

    /**
     * Lists all active tokens for the given integration.
     */
    fun listTokens(integrationId: String): List<TokenInfo>

    /**
     * Returns true if there is at least one active token for [integrationId].
     * Used to distinguish Pending vs Active integration state.
     */
    fun hasTokens(integrationId: String): Boolean
}

/**
 * A token pair returned from [TokenStore.createTokenPair] and [TokenStore.refreshToken].
 */
data class TokenPair(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)

/** Access token lifetime: 1 hour. */
const val ACCESS_TOKEN_TTL_MS: Long = 60L * 60 * 1000

/** Access token lifetime in seconds, for the `expires_in` OAuth response field. */
const val ACCESS_TOKEN_EXPIRES_IN_SECONDS: Long = 3600L

/** Refresh token lifetime: 30 days. */
const val REFRESH_TOKEN_TTL_MS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * Metadata about an issued token, for display in the authorized clients UI.
 */
data class TokenInfo(
    val integrationId: String,
    val clientId: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val label: String
)
