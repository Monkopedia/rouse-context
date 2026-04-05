package com.rousecontext.app.token

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an issued OAuth token.
 * Tokens are stored as SHA-256 hashes for security.
 */
@Entity(
    tableName = "tokens",
    indices = [
        Index(value = ["integrationId"]),
        Index(value = ["tokenHash"], unique = true)
    ]
)
data class TokenEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val integrationId: String,
    val clientId: String,
    val tokenHash: String,
    val label: String,
    val createdAt: Long,
    val lastUsedAt: Long
)
