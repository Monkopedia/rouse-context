package com.rousecontext.notifications.audit

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for audit log persistence.
 */
@Database(entities = [AuditEntry::class], version = 2, exportSchema = false)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun auditDao(): AuditDao

    companion object {
        private const val DB_NAME = "rouse_audit.db"

        /** Adds argumentsJson and resultJson columns to audit_entries. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit_entries ADD COLUMN argumentsJson TEXT")
                db.execSQL("ALTER TABLE audit_entries ADD COLUMN resultJson TEXT")
            }
        }

        fun create(context: Context): AuditDatabase =
            Room.databaseBuilder(context.applicationContext, AuditDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2)
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
