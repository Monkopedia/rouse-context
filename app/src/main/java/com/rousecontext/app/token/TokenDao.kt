package com.rousecontext.app.token

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data access object for token persistence.
 */
@Dao
interface TokenDao {

    @Insert
    fun insert(token: TokenEntity): Long

    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND tokenHash = :tokenHash LIMIT 1"
    )
    fun findByHash(integrationId: String, tokenHash: String): TokenEntity?

    @Query("UPDATE tokens SET lastUsedAt = :now WHERE id = :id")
    fun updateLastUsed(id: Long, now: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND tokenHash = :tokenHash")
    fun deleteByHash(integrationId: String, tokenHash: String)

    @Query("SELECT * FROM tokens WHERE integrationId = :integrationId ORDER BY createdAt DESC")
    fun listByIntegration(integrationId: String): List<TokenEntity>

    @Query("SELECT COUNT(*) FROM tokens WHERE integrationId = :integrationId")
    fun countByIntegration(integrationId: String): Int
}
