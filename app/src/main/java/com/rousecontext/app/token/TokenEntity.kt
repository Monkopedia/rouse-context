package com.rousecontext.app.token

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an issued OAuth token pair.
 * Access and refresh tokens are stored as SHA-256 hashes for security.
 *
 * Each pair belongs to a token [familyId] which is shared across rotated
 * descendants. Per OAuth 2.1 §4.14, rotated rows are retained (with
 * [rotatedAt] set) so that a subsequent redemption of a previously-rotated
 * refresh token can be detected as reuse and trigger revocation of the
 * entire family.
 */
@Entity(
    tableName = "tokens",
    indices = [
        Index(value = ["integrationId"]),
        Index(value = ["tokenHash"], unique = true),
        Index(value = ["refreshTokenHash"], unique = true),
        Index(value = ["familyId"])
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
    val familyId: String,
    val createdAt: Long,
    val lastUsedAt: Long,
    val expiresAt: Long,
    val refreshExpiresAt: Long,
    /**
     * Wall-clock time when this row's refresh token was rotated into a child,
     * or null if the refresh token has not (yet) been used. A non-null value
     * signals that any further redemption of this refresh token is a reuse
     * event and MUST trigger revocation of the whole [familyId].
     */
    val rotatedAt: Long? = null
)
