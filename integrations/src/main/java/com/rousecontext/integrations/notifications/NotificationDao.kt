package com.rousecontext.integrations.notifications

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * Data access object for captured notification records.
 */
@Dao
interface NotificationDao {

    @Insert
    suspend fun insert(record: NotificationRecord): Long

    @Query("UPDATE notification_records SET removedAt = :removedAt WHERE id = :id")
    suspend fun markRemoved(id: Long, removedAt: Long)

    @Query(
        "SELECT * FROM notification_records " +
            "WHERE postedAt >= :sinceMillis AND postedAt <= :untilMillis " +
            "AND (:packageFilter IS NULL OR packageName = :packageFilter) " +
            "AND (:textQuery IS NULL OR title LIKE '%' || :textQuery || '%' " +
            "OR text LIKE '%' || :textQuery || '%') " +
            "ORDER BY postedAt DESC " +
            "LIMIT :limit"
    )
    suspend fun search(
        sinceMillis: Long = 0,
        untilMillis: Long = Long.MAX_VALUE,
        packageFilter: String? = null,
        textQuery: String? = null,
        limit: Int = 50
    ): List<NotificationRecord>

    @Query(
        "SELECT packageName, COUNT(*) as count FROM notification_records " +
            "WHERE postedAt >= :sinceMillis AND postedAt <= :untilMillis " +
            "GROUP BY packageName ORDER BY count DESC"
    )
    suspend fun countByPackage(sinceMillis: Long, untilMillis: Long): List<PackageCount>

    @Query(
        "SELECT COUNT(*) FROM notification_records " +
            "WHERE postedAt >= :sinceMillis AND postedAt <= :untilMillis"
    )
    suspend fun countInRange(sinceMillis: Long, untilMillis: Long): Int

    @Query("DELETE FROM notification_records WHERE postedAt < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long): Int

    /**
     * Find a record by its posted timestamp and package name (used to correlate
     * removal events with posted events when the notification key changes).
     */
    @Query(
        "SELECT * FROM notification_records " +
            "WHERE packageName = :packageName AND postedAt = :postedAt " +
            "LIMIT 1"
    )
    suspend fun findByPackageAndTime(packageName: String, postedAt: Long): NotificationRecord?

    @Query(
        "UPDATE notification_records SET actionsTaken = :actionsTaken WHERE id = :id"
    )
    suspend fun updateActionsTaken(id: Long, actionsTaken: String)
}

/**
 * Projection for package-level notification counts.
 */
data class PackageCount(val packageName: String, val count: Int)
