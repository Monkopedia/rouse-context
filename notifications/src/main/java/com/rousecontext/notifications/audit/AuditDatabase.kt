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
@Database(
    entities = [AuditEntry::class, McpRequestEntry::class],
    version = 3,
    exportSchema = false
)
abstract class AuditDatabase : RoomDatabase() {

    abstract fun auditDao(): AuditDao

    abstract fun mcpRequestDao(): McpRequestDao

    companion object {
        private const val DB_NAME = "rouse_audit.db"

        /** Adds argumentsJson and resultJson columns to audit_entries. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE audit_entries ADD COLUMN argumentsJson TEXT")
                db.execSQL("ALTER TABLE audit_entries ADD COLUMN resultJson TEXT")
            }
        }

        /**
         * Adds the `mcp_request_entries` table for auditing every MCP
         * JSON-RPC request (not just tool calls). See issue #105.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mcp_request_entries` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`sessionId` TEXT NOT NULL, " +
                        "`provider` TEXT NOT NULL, " +
                        "`method` TEXT NOT NULL, " +
                        "`timestampMillis` INTEGER NOT NULL, " +
                        "`durationMillis` INTEGER NOT NULL, " +
                        "`resultBytes` INTEGER, " +
                        "`paramsJson` TEXT)"
                )
            }
        }

        fun create(context: Context): AuditDatabase =
            Room.databaseBuilder(context.applicationContext, AuditDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
