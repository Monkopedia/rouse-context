package com.rousecontext.app.token

import com.rousecontext.mcp.core.ACCESS_TOKEN_EXPIRES_IN_SECONDS
import com.rousecontext.mcp.core.ACCESS_TOKEN_TTL_MS
import com.rousecontext.mcp.core.REFRESH_TOKEN_TTL_MS
import com.rousecontext.mcp.core.TokenInfo
import com.rousecontext.mcp.core.TokenPair
import com.rousecontext.mcp.core.TokenStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * [TokenStore] implementation backed by Room.
 *
 * Tokens are generated as 32 random bytes encoded as base64url.
 * Only the SHA-256 hash of the token is persisted.
 */
class RoomTokenStore(
    private val dao: TokenDao
) : TokenStore {

    override fun validateToken(integrationId: String, token: String): Boolean {
        val hash = hashToken(token)
        val entity = dao.findByHash(integrationId, hash) ?: return false
        val now = System.currentTimeMillis()
        if (now > entity.expiresAt) return false
        dao.updateLastUsed(entity.id, now)
        return true
    }

    override fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?
    ): TokenPair {
        val accessRaw = generateRawToken()
        val accessToken = Base64.getUrlEncoder().withoutPadding().encodeToString(accessRaw)
        val refreshRaw = generateRawToken()
        val refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(refreshRaw)

        val now = System.currentTimeMillis()
        dao.insert(
            TokenEntity(
                integrationId = integrationId,
                clientId = clientId,
                tokenHash = hashToken(accessToken),
                refreshTokenHash = hashToken(refreshToken),
                label = clientName ?: clientId,
                createdAt = now,
                lastUsedAt = now,
                expiresAt = now + ACCESS_TOKEN_TTL_MS,
                refreshExpiresAt = now + REFRESH_TOKEN_TTL_MS
            )
        )

        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = ACCESS_TOKEN_EXPIRES_IN_SECONDS
        )
    }

    override fun revokeToken(integrationId: String, token: String) {
        dao.deleteByHash(integrationId, hashToken(token))
    }

    override fun revokeByClientId(integrationId: String, clientId: String) {
        dao.deleteByClientId(integrationId, clientId)
    }

    override fun refreshToken(integrationId: String, refreshToken: String): TokenPair? {
        val hash = hashToken(refreshToken)
        val entity = dao.findByRefreshHash(integrationId, hash) ?: return null
        val now = System.currentTimeMillis()
        if (now > entity.refreshExpiresAt) return null

        // Rotate: delete the old row, create a new pair
        dao.deleteById(entity.id)
        return createTokenPair(integrationId, entity.clientId, entity.label)
    }

    override fun listTokens(integrationId: String): List<TokenInfo> {
        return dao.listByIntegration(integrationId).map { entity ->
            TokenInfo(
                integrationId = entity.integrationId,
                clientId = entity.clientId,
                createdAt = entity.createdAt,
                lastUsedAt = entity.lastUsedAt,
                label = entity.label
            )
        }
    }

    override fun hasTokens(integrationId: String): Boolean {
        return dao.countByIntegration(integrationId) > 0
    }

    private fun generateRawToken(): ByteArray {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    companion object {
        private const val TOKEN_BYTES = 32

        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(token.toByteArray(Charsets.UTF_8)))
        }
    }
}
