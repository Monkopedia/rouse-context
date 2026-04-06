package com.rousecontext.notifications.capture

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for captured device notification history.
 *
 * Separate from [com.rousecontext.notifications.audit.AuditDatabase] to keep
 * audit log schema independent of notification capture concerns.
 */
@Database(entities = [NotificationRecord::class], version = 1, exportSchema = false)
abstract class NotificationDatabase : RoomDatabase() {

    abstract fun notificationDao(): NotificationDao

    companion object {
        private const val DB_NAME = "rouse_notifications.db"

        fun create(context: Context): NotificationDatabase = Room.databaseBuilder(
            context.applicationContext,
            NotificationDatabase::class.java,
            DB_NAME
        ).build()

        /**
         * Create an in-memory database for testing.
         */
        fun createInMemory(context: Context): NotificationDatabase = Room.inMemoryDatabaseBuilder(
            context.applicationContext,
            NotificationDatabase::class.java
        ).allowMainThreadQueries().build()
    }
}
