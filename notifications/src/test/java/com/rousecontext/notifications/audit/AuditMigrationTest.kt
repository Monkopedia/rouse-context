package com.rousecontext.notifications.audit

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for the `audit_entries` schema migration from v3 -> v4 that adds
 * the nullable `clientLabel` column (see issue #344). Runs under Robolectric so
 * a real SQLite engine is available without needing an instrumented device.
 *
 * The test hand-builds a v3 database using the pre-migration DDL, inserts a
 * row, executes [AuditDatabase.MIGRATION_3_4] directly, then:
 *   1. Opens the now-v4 database with Room so Room's built-in schema validation
 *      accepts the result -- this would fail with a `migration didn't properly
 *      handle` exception if the migration drifted from the entity definition.
 *   2. Verifies the pre-existing row is readable and its new `clientLabel`
 *      column is `null` (nullable column default).
 *   3. Verifies that inserting a fresh row with `clientLabel` populated
 *      round-trips correctly.
 */
@RunWith(RobolectricTestRunner::class)
class AuditMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: java.io.File
    private var helper: SupportSQLiteOpenHelper? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = java.io.File.createTempFile("audit-migration", ".db").apply { delete() }
    }

    @After
    fun tearDown() {
        helper?.close()
        dbFile.delete()
    }

    @Test
    fun `migrate from v3 to v4 adds clientLabel column without dropping rows`() {
        // 1) Create a v3-shaped database using raw DDL that matches the
        //    entity schema before this migration.
        val factory = FrameworkSQLiteOpenHelperFactory()
        val v3Helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile.absolutePath)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(V3_VERSION) {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            db.execSQL(AUDIT_V3_DDL)
                            db.execSQL(MCP_REQUEST_V3_DDL)
                        }

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int
                        ) {
                            // No-op; the test controls upgrades manually.
                        }
                    }
                )
                .build()
        )

        v3Helper.writableDatabase.use { db ->
            db.execSQL(
                "INSERT INTO audit_entries " +
                    "(sessionId, toolName, provider, timestampMillis, " +
                    "durationMillis, success, errorMessage, argumentsJson, resultJson) " +
                    "VALUES " +
                    "('session-pre', 'get_steps', 'health', 1000, 5, 1, NULL, '{}', '{}')"
            )
        }
        v3Helper.close()

        // 2) Re-open with Room at v4 -- this triggers MIGRATION_3_4
        //    automatically. Room's runtime validator then runs over the
        //    migrated schema and throws `IllegalStateException: ... didn't
        //    properly handle ...` if the migrated columns drift from the
        //    @Entity definition, so a clean open is itself a regression
        //    check that the ALTER TABLE leaves a schema compatible with
        //    AuditEntry.
        val roomDb = Room.databaseBuilder(context, AuditDatabase::class.java, dbFile.absolutePath)
            .addMigrations(
                AuditDatabase.MIGRATION_1_2,
                AuditDatabase.MIGRATION_2_3,
                AuditDatabase.MIGRATION_3_4
            )
            .allowMainThreadQueries()
            .build()

        try {
            val dao = roomDb.auditDao()
            val all = kotlinx.coroutines.runBlocking { dao.queryBySession("session-pre") }
            assertEquals(1, all.size)
            assertNull("Pre-migration row's clientLabel must be null", all.first().clientLabel)

            // Fresh row with clientLabel populated round-trips.
            val id = kotlinx.coroutines.runBlocking {
                dao.insert(
                    AuditEntry(
                        sessionId = "session-post",
                        toolName = "get_steps",
                        provider = "health",
                        timestampMillis = 2000,
                        durationMillis = 7,
                        success = true,
                        clientLabel = "Claude Desktop"
                    )
                )
            }
            val inserted = kotlinx.coroutines.runBlocking { dao.getById(id) }
            assertEquals("Claude Desktop", inserted?.clientLabel)
        } finally {
            roomDb.close()
        }
    }

    private companion object {
        private const val V3_VERSION = 3

        /** The audit_entries CREATE TABLE matching the @Entity at Room v3. */
        private const val AUDIT_V3_DDL =
            "CREATE TABLE IF NOT EXISTS `audit_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`toolName` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, " +
                "`durationMillis` INTEGER NOT NULL, " +
                "`success` INTEGER NOT NULL, " +
                "`errorMessage` TEXT, " +
                "`argumentsJson` TEXT, " +
                "`resultJson` TEXT" +
                ")"

        private const val MCP_REQUEST_V3_DDL =
            "CREATE TABLE IF NOT EXISTS `mcp_request_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`sessionId` TEXT NOT NULL, " +
                "`provider` TEXT NOT NULL, " +
                "`method` TEXT NOT NULL, " +
                "`timestampMillis` INTEGER NOT NULL, " +
                "`durationMillis` INTEGER NOT NULL, " +
                "`resultBytes` INTEGER, " +
                "`paramsJson` TEXT)"
    }
}
