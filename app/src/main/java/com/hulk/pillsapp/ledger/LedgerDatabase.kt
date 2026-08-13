package com.hulk.pillsapp.ledger

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        RawObservationEntity::class,
        ObservationRevisionEntity::class,
        CanonicalTransactionEntity::class,
        LedgerEntryEntity::class,
        EvidenceLinkEntity::class,
        ReconciliationRunEntity::class,
        CoverageGapEntity::class,
        NotificationRemovalEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun canonicalDao(): CanonicalDao
    abstract fun coverageGapDao(): CoverageGapDao
    abstract fun notificationRemovalDao(): NotificationRemovalDao
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        // 早期试验实现可能让一次通知修订关联多笔候选；保留最早链接，候选本身不删除。
        db.execSQL(
            "DELETE FROM evidence_link WHERE id NOT IN " +
                "(SELECT MIN(id) FROM evidence_link GROUP BY observation_id)"
        )
        db.execSQL("DROP INDEX IF EXISTS index_evidence_link_observation_id")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_evidence_link_observation_id " +
                "ON evidence_link(observation_id)"
        )
        db.execSQL(
            "UPDATE coverage_gap SET state = CASE " +
                "WHEN ended_at_ms IS NULL THEN 'ACTIVE' ELSE 'CLOSED' END WHERE state = 'OPEN'"
        )
    }
}
