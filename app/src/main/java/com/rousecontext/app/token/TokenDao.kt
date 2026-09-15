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

    /**
     * Compare-and-swap rotation: marks the row rotated **only if it is not
     * already**, and reports whether this caller was the one that did it.
     *
     * @return 1 if this call rotated the row, 0 if it was already rotated (by a
     *   concurrent redemption, or by an earlier one being replayed).
     *
     * This is the whole of OAuth 2.1 §4.14 reuse detection on this store. The
     * unconditional `UPDATE ... WHERE id = :id` it replaced made
     * [RoomTokenStore.refreshToken] a read -> check -> write with nothing
     * holding the gap: two concurrent redemptions of one refresh token both
     * read `rotatedAt IS NULL`, both wrote, and both minted a descendant, so
     * the family was never revoked and a stolen refresh token survived
     * indefinitely. Measured before the fix: 199 of 200 rounds double-minted.
     *
     * A single SQLite `UPDATE` is atomic, so the `rotatedAt IS NULL` predicate
     * and the write cannot be separated.
     *
     * That makes the *decision* atomic, and nothing more. The rotation as a
     * whole is not: consuming the parent, minting the child and revoking the
     * family are three statements, and issue #760 was a loser's
     * [deleteByFamilyId] landing between a winner's swap here and its
     * [insert]. Callers MUST therefore run this and whichever branch follows
     * inside one transaction -- see [RoomTokenStore.refreshToken].
     */
    @Query("UPDATE tokens SET rotatedAt = :rotatedAt WHERE id = :id AND rotatedAt IS NULL")
    fun markRotatedIfUnrotated(id: Long, rotatedAt: Long): Int

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND tokenHash = :tokenHash")
    fun deleteByHash(integrationId: String, tokenHash: String)

    @Query("DELETE FROM tokens WHERE id = :id")
    fun deleteById(id: Long)

    @Query("DELETE FROM tokens WHERE integrationId = :integrationId AND clientId = :clientId")
    fun deleteByClientId(integrationId: String, clientId: String)

    /**
     * Rewrites the display [label] for every row whose `(integrationId,
     * clientId)` matches. Used by the issue #345 one-shot upgrade from the
     * literal `"unknown"` label to the monotonic `Unknown (#N)` label.
     */
    @Query(
        "UPDATE tokens SET label = :label " +
            "WHERE integrationId = :integrationId AND clientId = :clientId"
    )
    fun updateLabelByClientId(integrationId: String, clientId: String, label: String)

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
