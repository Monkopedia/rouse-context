package com.rousecontext.app.token

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an issued OAuth token pair.
 * Access and refresh tokens are stored as SHA-256 hashes for security.
 */
@Entity(
    tableName = "tokens",
    indices = [
        Index(value = ["integrationId"]),
        Index(value = ["tokenHash"], unique = true),
        Index(value = ["refreshTokenHash"], unique = true)
    ]
)
data class TokenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val integrationId: String,
    val clientId: String,
    val tokenHash: String,
    val refreshTokenHash: String,
    val label: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val refreshExpiresAt: Long
)
