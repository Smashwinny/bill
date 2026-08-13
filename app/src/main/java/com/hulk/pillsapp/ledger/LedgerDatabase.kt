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
        DebtAccountEntity::class,
        DebtAccountEvidenceEntity::class,
        AccountDiscoveryScanEntity::class,
        ReconciliationRunEntity::class,
        CoverageGapEntity::class,
        NotificationRemovalEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun canonicalDao(): CanonicalDao
    abstract fun debtAccountDao(): DebtAccountDao
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

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `debt_account` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`public_id` TEXT NOT NULL, `cluster_hash` TEXT NOT NULL, `identity_hash` TEXT, " +
                "`product` TEXT NOT NULL, " +
                "`institution_code` TEXT NOT NULL, `institution_label` TEXT NOT NULL, " +
                "`display_label` TEXT NOT NULL, `masked_suffix` TEXT, `user_handle` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, `status` TEXT NOT NULL, `confidence` INTEGER NOT NULL, " +
                "`last_event_kind` TEXT NOT NULL, `last_evidence_strength` TEXT NOT NULL, " +
                "`due_day_of_month` INTEGER, `first_seen_at_ms` INTEGER NOT NULL, " +
                "`last_seen_at_ms` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
                "`updated_at_ms` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_account_public_id` " +
                "ON `debt_account` (`public_id`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_account_cluster_hash` " +
                "ON `debt_account` (`cluster_hash`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_account_identity_hash` " +
                "ON `debt_account` (`identity_hash`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_debt_account_status` ON `debt_account` (`status`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `debt_account_evidence` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `observation_id` INTEGER NOT NULL, " +
                "`account_id` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, `parser_version` INTEGER NOT NULL, " +
                "`signal_fingerprint` TEXT NOT NULL, `is_current` INTEGER NOT NULL, " +
                "`event_kind` TEXT NOT NULL, `strength` TEXT NOT NULL, " +
                "`amount_role` TEXT NOT NULL, `amount_cents` INTEGER, `due_day_of_month` INTEGER, " +
                "`observed_at_ms` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, " +
                "FOREIGN KEY(`observation_id`) REFERENCES `raw_observation`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                "FOREIGN KEY(`account_id`) REFERENCES `debt_account`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_debt_account_evidence_observation_id_content_hash_parser_version_signal_fingerprint` " +
                "ON `debt_account_evidence` (`observation_id`, `content_hash`, `parser_version`, `signal_fingerprint`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_debt_account_evidence_account_id` " +
                "ON `debt_account_evidence` (`account_id`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_debt_account_evidence_event_kind` " +
                "ON `debt_account_evidence` (`event_kind`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_debt_account_evidence_observation_id_is_current` " +
                "ON `debt_account_evidence` (`observation_id`, `is_current`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `account_discovery_scan` (" +
                "`observation_id` INTEGER NOT NULL, `content_hash` TEXT NOT NULL, " +
                "`parser_version` INTEGER NOT NULL, `is_current` INTEGER NOT NULL, " +
                "`result` TEXT NOT NULL, `scanned_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`observation_id`, `content_hash`, `parser_version`), " +
                "FOREIGN KEY(`observation_id`) REFERENCES `raw_observation`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_account_discovery_scan_parser_version` " +
                "ON `account_discovery_scan` (`parser_version`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_account_discovery_scan_observation_id_is_current` " +
                "ON `account_discovery_scan` (`observation_id`, `is_current`)"
        )
    }
}
