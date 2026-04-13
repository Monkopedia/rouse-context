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

    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND tokenHash = :tokenHash LIMIT 1"
    )
    fun findByHash(integrationId: String, tokenHash: String): TokenEntity?

    @Query(
        "SELECT * FROM tokens " +
            "WHERE integrationId = :integrationId " +
            "AND refreshTokenHash = :refreshTokenHash LIMIT 1"
    )
    fun findByRefreshHash(integrationId: String, refreshTokenHash: String): TokenEntity?

    @Query("UPDATE tokens SET lastUsedAt = :now WHERE id = :id")
    fun updateLastUsed(id: Long, now: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND tokenHash = :tokenHash")
    fun deleteByHash(integrationId: String, tokenHash: String)

    @Query("DELETE FROM tokens WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND clientId = :clientId")
    fun deleteByClientId(integrationId: String, clientId: String)

    @Query("SELECT * FROM tokens WHERE integrationId = :integrationId ORDER BY createdAt DESC")
    fun listByIntegration(integrationId: String): List<TokenEntity>

    /**
     * Reactive version of [listByIntegration]. Room re-emits whenever the tokens
     * table changes, allowing UI to live-update the authorized clients list.
     */
    @Query("SELECT * FROM tokens WHERE integrationId = :integrationId ORDER BY createdAt DESC")
    fun observeByIntegration(integrationId: String): Flow<List<TokenEntity>>

    @Query("SELECT COUNT(*) FROM tokens WHERE integrationId = :integrationId")
    fun countByIntegration(integrationId: String): Int
}
