package com.hulk.manualledger

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualLedgerMigrationTest {
    private val databaseName = "manual-ledger-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ManualLedgerDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationFromVersionOnePreservesTransactionsAndAddsCategoryTreeStorage() {
        helper.createDatabase(databaseName, 1).apply {
            execSQL(
                """INSERT INTO manual_transaction
                    (id,type,amount_cents,currency,category,account,target_account,occurred_at_ms,note,created_at_ms,updated_at_ms)
                    VALUES ('legacy','EXPENSE',1234,'CNY','其他 › API','现金',NULL,1700000000000,NULL,1,1)
                """.trimIndent(),
            )
            close()
        }

        helper.runMigrationsAndValidate(databaseName, 2, true, MANUAL_LEDGER_MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT id, category, category_id FROM manual_transaction").use { cursor ->
                cursor.moveToFirst()
                assertEquals("legacy", cursor.getString(0))
                assertEquals("其他 › API", cursor.getString(1))
                assertEquals(null, cursor.getString(2))
            }
            migrated.query("SELECT COUNT(*) FROM ledger_category").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }
}
