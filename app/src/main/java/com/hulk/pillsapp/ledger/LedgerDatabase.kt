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
        BehaviorSignalReceiptEntity::class,
        BehaviorCandidateEntity::class,
        BehaviorTemplateEntity::class,
        BehaviorDecisionEntity::class,
        DebtAccountEntity::class,
        DebtAccountEvidenceEntity::class,
        AccountDiscoveryScanEntity::class,
        ReconciliationRunEntity::class,
        CoverageGapEntity::class,
        NotificationRemovalEntity::class,
        StatementImportEntity::class,
        StatementArtifactChunkEntity::class,
        StatementRowEntity::class,
        StatementImportRowEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun canonicalDao(): CanonicalDao
    abstract fun behaviorDao(): BehaviorDao
    abstract fun debtAccountDao(): DebtAccountDao
    abstract fun coverageGapDao(): CoverageGapDao
    abstract fun notificationRemovalDao(): NotificationRemovalDao
    abstract fun statementDao(): StatementDao
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

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `statement_import` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `public_id` TEXT NOT NULL, " +
                "`file_hash` TEXT NOT NULL, `display_name` TEXT NOT NULL, `source_kind` TEXT NOT NULL, " +
                "`format` TEXT NOT NULL, `parser_version` INTEGER NOT NULL, `authority` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `observed_row_from_ms` INTEGER NOT NULL, " +
                "`observed_row_to_ms` INTEGER NOT NULL, `raw_row_count` INTEGER NOT NULL, " +
                "`valid_row_count` INTEGER NOT NULL, `invalid_row_count` INTEGER NOT NULL, " +
                "`ignored_footer_row_count` INTEGER NOT NULL, " +
                "`duplicate_row_count` INTEGER NOT NULL, `artifact_size_bytes` INTEGER NOT NULL, " +
                "`artifact_chunk_count` INTEGER NOT NULL, `imported_at_ms` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_statement_import_public_id` " +
                "ON `statement_import` (`public_id`)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_statement_import_file_hash_parser_version` " +
                "ON `statement_import` (`file_hash`, `parser_version`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `statement_artifact_chunk` (" +
                "`import_id` INTEGER NOT NULL, `chunk_index` INTEGER NOT NULL, " +
                "`chunk_hash` TEXT NOT NULL, `bytes` BLOB NOT NULL, " +
                "PRIMARY KEY(`import_id`, `chunk_index`), " +
                "FOREIGN KEY(`import_id`) REFERENCES `statement_import`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `statement_row` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `source_kind` TEXT NOT NULL, " +
                "`row_fingerprint` TEXT NOT NULL, `external_id_hash` TEXT, " +
                "`occurred_at_ms` INTEGER NOT NULL, `amount_cents` INTEGER NOT NULL, " +
                "`currency` TEXT NOT NULL, `direction` TEXT NOT NULL, `tx_type` TEXT NOT NULL, " +
                "`tx_status` TEXT NOT NULL, `counterparty` TEXT, `item_description` TEXT, " +
                "`raw_record` TEXT NOT NULL, `created_at_ms` INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_statement_row_row_fingerprint` " +
                "ON `statement_row` (`row_fingerprint`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_statement_row_external_id_hash` " +
                "ON `statement_row` (`external_id_hash`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_statement_row_occurred_at_ms` " +
                "ON `statement_row` (`occurred_at_ms`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `statement_import_row` (" +
                "`import_id` INTEGER NOT NULL, `row_id` INTEGER NOT NULL, " +
                "`source_row_number` INTEGER NOT NULL, PRIMARY KEY(`import_id`, `source_row_number`), " +
                "FOREIGN KEY(`import_id`) REFERENCES `statement_import`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                "FOREIGN KEY(`row_id`) REFERENCES `statement_row`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_statement_import_row_row_id` " +
                "ON `statement_import_row` (`row_id`)"
        )
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `behavior_signal_receipt` (" +
                "`occurrence_id` TEXT NOT NULL, `observation_id` INTEGER NOT NULL, " +
                "`ambiguous_repeat` INTEGER NOT NULL, `applied_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`occurrence_id`), " +
                "FOREIGN KEY(`observation_id`) REFERENCES `raw_observation`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_behavior_signal_receipt_observation_id` " +
                "ON `behavior_signal_receipt` (`observation_id`)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `behavior_template` (" +
                "`template_key` TEXT NOT NULL, `package_name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`route_signature` TEXT NOT NULL, `app_version_code` INTEGER NOT NULL, " +
                "`positive_count` INTEGER NOT NULL, `negative_count` INTEGER NOT NULL, " +
                "`consecutive_positive_count` INTEGER NOT NULL, `auto_enabled` INTEGER NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, " +
                "PRIMARY KEY(`template_key`))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `behavior_candidate` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `public_id` TEXT NOT NULL, " +
                "`observation_id` INTEGER NOT NULL, `canonical_tx_id` INTEGER NOT NULL, " +
                "`template_key` TEXT NOT NULL, `package_name` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`amount_cents` INTEGER, `currency` TEXT NOT NULL, `occurred_at_ms` INTEGER NOT NULL, " +
                "`confidence` INTEGER NOT NULL, `consumed_intent` INTEGER NOT NULL, " +
                "`route_signature` TEXT NOT NULL, `app_version_code` INTEGER NOT NULL, " +
                "`ambiguous_repeat_count` INTEGER NOT NULL, " +
                "`feature_summary` TEXT NOT NULL, `purpose` TEXT, `state` TEXT NOT NULL, " +
                "`created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, " +
                "`decided_at_ms` INTEGER, " +
                "FOREIGN KEY(`observation_id`) REFERENCES `raw_observation`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION, " +
                "FOREIGN KEY(`canonical_tx_id`) REFERENCES `canonical_transaction`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_candidate_public_id` ON `behavior_candidate` (`public_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_candidate_observation_id` ON `behavior_candidate` (`observation_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_behavior_candidate_canonical_tx_id` ON `behavior_candidate` (`canonical_tx_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_candidate_template_key` ON `behavior_candidate` (`template_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_candidate_state` ON `behavior_candidate` (`state`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `behavior_decision` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `candidate_id` INTEGER NOT NULL, " +
                "`decision` TEXT NOT NULL, `actor` TEXT NOT NULL, `kind` TEXT NOT NULL, " +
                "`amount_cents` INTEGER, `purpose` TEXT, `created_at_ms` INTEGER NOT NULL, " +
                "FOREIGN KEY(`candidate_id`) REFERENCES `behavior_candidate`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE NO ACTION)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_behavior_decision_candidate_id` ON `behavior_decision` (`candidate_id`)")
    }
}
