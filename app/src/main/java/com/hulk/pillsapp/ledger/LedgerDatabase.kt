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
    version = 1,
    exportSchema = true,
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun observationDao(): ObservationDao
    abstract fun canonicalDao(): CanonicalDao
    abstract fun coverageGapDao(): CoverageGapDao
    abstract fun notificationRemovalDao(): NotificationRemovalDao
}
