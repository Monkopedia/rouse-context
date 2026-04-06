package com.rousecontext.notifications.capture

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a captured device notification.
 */
@Entity(tableName = "notification_records")
data class NotificationRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
    val removedAt: Long? = null,
    val category: String?,
    val ongoing: Boolean,
    val actionsTaken: String? = null
)
