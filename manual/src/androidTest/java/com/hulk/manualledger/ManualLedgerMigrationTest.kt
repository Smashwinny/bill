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

        helper.runMigrationsAndValidate(databaseName, 3, true, MANUAL_LEDGER_MIGRATION_1_2, MANUAL_LEDGER_MIGRATION_2_3).use { migrated ->
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
    @Test
    fun migrationFromTwoKeepsExistingRowsOutOfTrash() {
        helper.createDatabase("trash-migration", 2).apply {
            execSQL("""INSERT INTO manual_transaction
                (id,type,amount_cents,currency,category,account,target_account,occurred_at_ms,note,created_at_ms,updated_at_ms,category_id)
                VALUES ('existing','EXPENSE',1234,'CNY','餐饮','现金',NULL,1,'原始备注',1,1,NULL)""")
            close()
        }
        helper.runMigrationsAndValidate("trash-migration", 3, true, MANUAL_LEDGER_MIGRATION_2_3).use { db ->
            db.query("SELECT amount_cents, note, deleted_at_ms FROM manual_transaction WHERE id = 'existing'").use {
                it.moveToFirst()
                assertEquals(1234, it.getInt(0))
                assertEquals("原始备注", it.getString(1))
                assertEquals(true, it.isNull(2))
            }
        }
    }

}
