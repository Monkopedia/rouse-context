package com.rousecontext.mcp.core

import kotlin.random.Random

/**
 * In-memory implementation of [TokenStore] for testing and as a reference.
 *
 * Tokens are generated as 32-byte base64url strings. The app layer will provide
 * its own implementation backed by Room with hashed tokens.
 */
class InMemoryTokenStore(
    private val clock: Clock = SystemClock
) : TokenStore {

    private data class StoredToken(
        val integrationId: String,
        val clientId: String,
        val clientName: String?,
        val token: String,
        val createdAt: Long,
        var lastUsedAt: Long,
        var revoked: Boolean = false
    )

    private val tokens = mutableListOf<StoredToken>()

    override fun validateToken(integrationId: String, token: String): Boolean {
        synchronized(this) {
            val stored = tokens.find { it.token == token && !it.revoked }
                ?: return false
            if (stored.integrationId != integrationId) return false
            stored.lastUsedAt = clock.currentTimeMillis()
            return true
        }
    }

    override fun createToken(integrationId: String, clientId: String, clientName: String?): String {
        val token = generateToken()
        val now = clock.currentTimeMillis()
        synchronized(this) {
            tokens.add(
                StoredToken(
                    integrationId = integrationId,
                    clientId = clientId,
                    clientName = clientName,
                    token = token,
                    createdAt = now,
                    lastUsedAt = now
                )
            )
        }
        return token
    }

    override fun revokeToken(integrationId: String, token: String) {
        synchronized(this) {
            tokens.filter { it.integrationId == integrationId && it.token == token }
                .forEach { it.revoked = true }
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
