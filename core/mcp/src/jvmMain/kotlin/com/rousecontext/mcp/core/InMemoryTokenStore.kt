package com.rousecontext.mcp.core

import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory implementation of [TokenStore] for testing and as a reference.
 *
 * Tokens are generated as 32-byte base64url strings. The app layer will provide
 * its own implementation backed by Room with hashed tokens.
 */
class InMemoryTokenStore(private val clock: Clock = SystemClock) : TokenStore {

    private data class StoredToken(
        val integrationId: String,
        val clientId: String,
        val clientName: String?,
        val familyId: String,
        val token: String,
        val createdAt: Long,
        val expiresAt: Long,
        var lastUsedAt: Long,
        var revoked: Boolean = false
    )

    private data class StoredRefreshToken(
        val integrationId: String,
        val clientId: String,
        val clientName: String?,
        val familyId: String,
        val refreshToken: String,
        val createdAt: Long,
        val expiresAt: Long,
        var revoked: Boolean = false,
        // Set when this refresh token has been rotated into a child. A subsequent
        // redemption of the same refresh token is a reuse signal (OAuth 2.1 §4.14).
        var rotatedAt: Long? = null
    )

    private val tokens = mutableListOf<StoredToken>()
    private val refreshTokens = mutableListOf<StoredRefreshToken>()

    /**
     * Bumped whenever the token set changes so [tokensFlow] re-emits. The value
     * itself is meaningless — consumers re-read [listTokens] on each emission.
     */
    private val changeTick = MutableStateFlow(0)

    private fun notifyChanged() {
        changeTick.value = changeTick.value + 1
    }

    override fun validateToken(integrationId: String, token: String): Boolean {
        synchronized(this) {
            val stored = tokens.find { it.token == token && !it.revoked }
                ?: return false
            if (stored.integrationId != integrationId) return false
            if (clock.currentTimeMillis() > stored.expiresAt) return false
            stored.lastUsedAt = clock.currentTimeMillis()
            return true
        }
    }

    override fun resolveClientName(integrationId: String, token: String): String? {
        synchronized(this) {
            val stored = tokens.find { it.token == token && !it.revoked } ?: return null
            if (stored.integrationId != integrationId) return null
            return stored.clientName
        }
    }

    override fun resolveClientId(integrationId: String, token: String): String? {
        synchronized(this) {
            val stored = tokens.find { it.token == token && !it.revoked } ?: return null
            if (stored.integrationId != integrationId) return null
            return stored.clientId
        }
    }

    override fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?
    ): TokenPair = createTokenPair(
        integrationId = integrationId,
        clientId = clientId,
        clientName = clientName,
        familyId = UUID.randomUUID().toString()
    )

    private fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?,
        familyId: String
    ): TokenPair {
        val accessToken = generateToken()
        val refreshToken = generateToken()
        val now = clock.currentTimeMillis()
        synchronized(this) {
            tokens.add(
                StoredToken(
                    integrationId = integrationId,
                    clientId = clientId,
                    clientName = clientName,
                    familyId = familyId,
                    token = accessToken,
                    createdAt = now,
                    expiresAt = now + ACCESS_TOKEN_TTL_MS,
                    lastUsedAt = now
                )
            )
            refreshTokens.add(
                StoredRefreshToken(
                    integrationId = integrationId,
                    clientId = clientId,
                    clientName = clientName,
                    familyId = familyId,
                    refreshToken = refreshToken,
                    createdAt = now,
                    expiresAt = now + REFRESH_TOKEN_TTL_MS
                )
            )
        }
        notifyChanged()
        return TokenPair(
            accessToken = accessToken,
            refreshToken = refreshToken,
            expiresIn = ACCESS_TOKEN_EXPIRES_IN_SECONDS
        )
    }

    override fun revokeToken(integrationId: String, token: String) {
        synchronized(this) {
            tokens.filter { it.integrationId == integrationId && it.token == token }
                .forEach { it.revoked = true }
        }
        notifyChanged()
    }

    override fun revokeByClientId(integrationId: String, clientId: String) {
        synchronized(this) {
            tokens.filter { it.integrationId == integrationId && it.clientId == clientId }
                .forEach { it.revoked = true }
            refreshTokens.filter { it.integrationId == integrationId && it.clientId == clientId }
                .forEach { it.revoked = true }
        }
        notifyChanged()
    }

    override fun refreshToken(integrationId: String, refreshToken: String): TokenPair? {
        val parent = consumeRefreshToken(integrationId, refreshToken) ?: return null
        return createTokenPair(integrationId, parent.clientId, parent.clientName, parent.familyId)
    }

    /**
     * Atomically validates and consumes [refreshToken]. Returns the parent
     * refresh-token row on success, or null if the token is unknown, scoped
     * to a different integration, already revoked, expired, or replayed
     * (OAuth 2.1 §4.14 reuse detection, which also revokes the family as a
     * side effect).
     */
    private fun consumeRefreshToken(
        integrationId: String,
        refreshToken: String
    ): StoredRefreshToken? = synchronized(this) {
        // Search regardless of revoked/rotated so we can detect replay of a
        // previously-rotated refresh token.
        val stored = refreshTokens.find { it.refreshToken == refreshToken }
        if (stored == null || stored.integrationId != integrationId) return null

        if (stored.rotatedAt != null) {
            // Reuse detected: revoke the entire token family.
            revokeFamily(stored.familyId)
            return null
        }

        if (stored.revoked || clock.currentTimeMillis() > stored.expiresAt) return null

        // Mark this refresh as rotated (kept around for future reuse detection)
        // and revoke the sibling access tokens from this rotation step.
        stored.rotatedAt = clock.currentTimeMillis()
        tokens.filter {
            it.integrationId == integrationId &&
                it.familyId == stored.familyId &&
                !it.revoked
        }.forEach { it.revoked = true }
        stored
    }

    private fun revokeFamily(familyId: String) {
        tokens.filter { it.familyId == familyId }.forEach { it.revoked = true }
        refreshTokens.filter { it.familyId == familyId }.forEach { it.revoked = true }
        notifyChanged()
    }

    override fun listTokens(integrationId: String): List<TokenInfo> {
        synchronized(this) {
            return tokens
                .filter { it.integrationId == integrationId && !it.revoked }
                .map {
                    TokenInfo(
                        integrationId = it.integrationId,
                        clientId = it.clientId,
                        createdAt = it.createdAt,
                        lastUsedAt = it.lastUsedAt,
                        label = it.clientName ?: it.clientId
                    )
                }
        }
    }

    override fun tokensFlow(integrationId: String): Flow<List<TokenInfo>> =
        changeTick.map { listTokens(integrationId) }

    override fun hasTokens(integrationId: String): Boolean {
        synchronized(this) {
            return tokens.any { it.integrationId == integrationId && !it.revoked }
        }
    }

    private fun generateToken(): String {
        val bytes = Random.nextBytes(32)
        return bytes.encodeBase64Url()
    }
}

/**
 * Base64url encoding without padding, per RFC 4648 section 5.
 */
internal fun ByteArray.encodeBase64Url(): String {
    val table = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val sb = StringBuilder()
    var i = 0
    while (i < size) {
        val b0 = this[i].toInt() and 0xFF
        sb.append(table[b0 shr 2])
        if (i + 1 < size) {
            val b1 = this[i + 1].toInt() and 0xFF
            sb.append(table[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (i + 2 < size) {
                val b2 = this[i + 2].toInt() and 0xFF
                sb.append(table[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                sb.append(table[b2 and 0x3F])
            } else {
                sb.append(table[(b1 and 0x0F) shl 2])
            }
        } else {
            sb.append(table[(b0 and 0x03) shl 4])
        }
        i += 3
    }
    return sb.toString()
}
