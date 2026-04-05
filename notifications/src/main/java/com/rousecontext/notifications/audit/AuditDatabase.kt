package com.rousecontext.notifications.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database for audit log persistence.
 */
@Database(entities = [AuditEntry::class], version = 1, exportSchema = false)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun auditDao(): AuditDao

    companion object {
        private const val DB_NAME = "rouse_audit.db"

        fun create(context: Context): AuditDatabase =
            Room.databaseBuilder(context.applicationContext, AuditDatabase::class.java, DB_NAME)
                .build()

        /**
         * Create an in-memory database for testing.
         */
        fun createInMemory(context: Context): AuditDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AuditDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
