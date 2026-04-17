package com.rousecontext.app.token

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for token persistence.
 */
@Dao
interface TokenDao {

    @Insert
    fun insert(token: TokenEntity): Long

    /**
     * Lookup for access-token validation. Rotated rows MUST NOT validate
     * (their access token was revoked at rotation time).
     */
    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND tokenHash = :tokenHash " +
            "AND rotatedAt IS NULL LIMIT 1"
    )
    fun findByHash(integrationId: String, tokenHash: String): TokenEntity?

    /**
     * Lookup for refresh-token redemption. Returns the row regardless of
     * rotation state so that reuse of a rotated refresh token can be
     * detected by the caller.
     */
    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND refreshTokenHash = :refreshTokenHash LIMIT 1"
    )
    fun findByRefreshHash(integrationId: String, refreshTokenHash: String): TokenEntity?

    @Query("UPDATE tokens SET lastUsedAt = :now WHERE id = :id")
    fun updateLastUsed(id: Long, now: Long)

    @Query("UPDATE tokens SET rotatedAt = :rotatedAt WHERE id = :id")
    fun markRotated(id: Long, rotatedAt: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND tokenHash = :tokenHash")
    fun deleteByHash(integrationId: String, tokenHash: String)

    @Query("DELETE FROM tokens WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND clientId = :clientId")
    fun deleteByClientId(integrationId: String, clientId: String)

    /**
     * Revokes an entire token family by deleting every row with the given
     * [familyId]. Used when a rotated refresh token is replayed, per OAuth
     * 2.1 §4.14.
     */
    @Query("DELETE FROM tokens WHERE familyId = :familyId")
    fun deleteByFamilyId(familyId: String)

    /**
     * Lists active tokens (rotated rows excluded — they hold stale access
     * tokens that were revoked at rotation time).
     */
    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND rotatedAt IS NULL " +
            "ORDER BY createdAt DESC"
    )
    fun listByIntegration(integrationId: String): List<TokenEntity>

    /**
     * Reactive version of [listByIntegration]. Room re-emits whenever the tokens
     * table changes, allowing UI to live-update the authorized clients list.
     */
    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND rotatedAt IS NULL " +
            "ORDER BY createdAt DESC"
    )
    fun observeByIntegration(integrationId: String): Flow<List<TokenEntity>>

    @Query(
        "SELECT COUNT(*) FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND rotatedAt IS NULL"
    )
    fun countByIntegration(integrationId: String): Int
}
