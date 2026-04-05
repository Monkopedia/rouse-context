package com.rousecontext.app.token

import com.rousecontext.mcp.core.TokenInfo
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
        dao.updateLastUsed(entity.id, System.currentTimeMillis())
        return true
    }

    override fun createToken(integrationId: String, clientId: String): String {
        val raw = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(raw)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

        val now = System.currentTimeMillis()
        dao.insert(
            TokenEntity(
                integrationId = integrationId,
                clientId = clientId,
                tokenHash = hashToken(token),
                label = clientId,
                createdAt = now,
                lastUsedAt = now
            )
        )

        return token
    }

    override fun revokeToken(integrationId: String, token: String) {
        dao.deleteByHash(integrationId, hashToken(token))
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

    companion object {
        private const val TOKEN_BYTES = 32

        fun hashToken(token: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(digest.digest(token.toByteArray(Charsets.UTF_8)))
        }
    }
}
