package com.rousecontext.mcp.core

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
        val refreshToken: String,
        val createdAt: Long,
        val expiresAt: Long,
        var revoked: Boolean = false
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

    override fun createTokenPair(
        integrationId: String,
        clientId: String,
        clientName: String?
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
        synchronized(this) {
            val stored = refreshTokens.find {
                it.refreshToken == refreshToken && !it.revoked
            } ?: return null
            if (stored.integrationId != integrationId) return null
            if (clock.currentTimeMillis() > stored.expiresAt) return null

            // Rotate: invalidate old refresh token and revoke existing access tokens
            stored.revoked = true
            tokens.filter {
                it.integrationId == integrationId &&
                    it.clientId == stored.clientId &&
                    !it.revoked
            }.forEach { it.revoked = true }

            return createTokenPair(integrationId, stored.clientId, stored.clientName)
        }
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
