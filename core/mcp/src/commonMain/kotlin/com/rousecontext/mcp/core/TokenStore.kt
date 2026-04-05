package com.rousecontext.mcp.core

/**
 * Token verification and management, scoped per integration.
 *
 * The app implements this backed by Room. Tokens are opaque strings (32 bytes base64url),
 * stored as hashes. Each token is scoped to a single integration — a token issued for
 * `/health` cannot access `/notifications`.
 */
interface TokenStore {

    /**
     * Validates that [token] is a currently active token for [integrationId].
     * Returns false for revoked tokens, tokens belonging to other integrations,
     * or unknown tokens.
     */
    fun validateToken(integrationId: String, token: String): Boolean

    /**
     * Creates and returns a new access token for [integrationId], associated with
     * the given [clientId]. The returned token is the raw value the client should
     * use as a Bearer token.
     */
    fun createToken(integrationId: String, clientId: String): String

    /**
     * Revokes a specific token. No-op if the token does not exist.
     */
    fun revokeToken(integrationId: String, token: String)

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
 * Metadata about an issued token, for display in the authorized clients UI.
 */
data class TokenInfo(
    val integrationId: String,
    val clientId: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val label: String
)
