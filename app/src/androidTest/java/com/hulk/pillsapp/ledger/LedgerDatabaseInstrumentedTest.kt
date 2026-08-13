package com.hulk.pillsapp.ledger

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hulk.pillsapp.sha256Hex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        LedgerDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )
    private lateinit var db: LedgerDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            LedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun observation(body: String, hash: String) = RawObservationEntity(
        source = ObservationSource.NOTIFICATION,
        sourceKey = "0:notification-key",
        userHandle = 0,
        packageName = "com.example.pay",
        postTimeMs = 1000,
        receivedAtMs = 1100,
        title = "支付提醒",
        body = body,
        contentHash = hash,
        capturePath = CapturePath.LIVE_CALLBACK,
        parseState = ParseState.PENDING_PARSE,
        createdAtMs = 1100,
    )

    @Test
    fun notificationRevisionKeepsOneObservationOneCandidateAndFullHistory() {
        val first = db.observationDao().ingest(observation("正在支付 10.00 元", "hash-processing"))
        val observationId = first.id
        CandidatePromoter.process(db, observationId)

        db.observationDao().ingest(observation("支付成功 10.00 元", "hash-success"))
        CandidatePromoter.process(db, observationId)

        assertEquals(1L, db.observationDao().countAll())
        assertEquals(2L, db.observationDao().countRevisions(observationId))
        assertEquals(1L, db.canonicalDao().countAll())
        assertEquals(1L, db.canonicalDao().countEvidenceForObservation(observationId))
    }

    @Test
    fun duplicateDeliveryOnlyIncrementsCounter() {
        val first = db.observationDao().ingest(observation("支付成功 10.00 元", "same-hash"))
        db.observationDao().ingest(observation("支付成功 10.00 元", "same-hash"))

        assertEquals(1L, db.observationDao().countAll())
        assertEquals(1L, db.observationDao().findById(first.id)?.duplicateCount)
        assertEquals(1L, db.observationDao().countRevisions(first.id))
    }

    @Test
    fun closingGapRemovesItFromActiveCountButPreservesHistory() {
        val dao = db.coverageGapDao()
        dao.insert(
            CoverageGapEntity(
                detector = GapDetectors.LISTENER_CALLBACK,
                startedAtMs = 1000,
                endedAtMs = null,
                state = GapState.ACTIVE,
                note = "test",
            )
        )
        assertEquals(1L, dao.countOpen())
        dao.closeOpenByDetector(GapDetectors.LISTENER_CALLBACK, 2000)
        assertEquals(0L, dao.countOpen())
        assertEquals(0, dao.openGaps().size)
    }

    @Test
    fun legacyOpenGapStateIsNormalizedWithoutDeletingRows() {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO coverage_gap(detector, started_at_ms, ended_at_ms, state, note) VALUES('legacy-active', 1000, NULL, 'OPEN', NULL)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO coverage_gap(detector, started_at_ms, ended_at_ms, state, note) VALUES('legacy-closed', 1000, 2000, 'OPEN', NULL)"
        )
        assertEquals(2, db.coverageGapDao().normalizeLegacyOpenState())
        assertEquals(1L, db.coverageGapDao().countOpen())
    }

    @Test
    fun migrationOneToTwoPreservesRowsAndCreatesUniqueEvidenceIndex() {
        val name = "migration-1-2.db"
        migrationHelper.createDatabase(name, 1).apply {
            execSQL(
                "INSERT INTO raw_observation(id, source, source_key, user_handle, package_name, post_time_ms, received_at_ms, title, body, content_hash, capture_path, parse_state, duplicate_count, created_at_ms) " +
                    "VALUES(1, 'NOTIFICATION', '0:key', 0, 'com.example.pay', 1000, 1001, '支付', '10元', 'h', 'LIVE_CALLBACK', 'PARSED', 0, 1001)"
            )
            execSQL(
                "INSERT INTO canonical_transaction(id, strong_id_hash, type, status, amount_cents, currency, merchant_hint, occurred_at_ms, backfilled_from, created_at_ms) " +
                    "VALUES(1, NULL, 'PAYMENT', 'DETECTED', 1000, 'CNY', NULL, 1000, NULL, 1001)"
            )
            execSQL(
                "INSERT INTO evidence_link(id, observation_id, canonical_tx_id, match_reason, created_at_ms) " +
                    "VALUES(1, 1, 1, 'WEAK_OBSERVATION_ONLY', 1001)"
            )
            execSQL(
                "INSERT INTO coverage_gap(id, detector, started_at_ms, ended_at_ms, state, note) " +
                    "VALUES(1, 'legacy', 1000, 2000, 'OPEN', NULL)"
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 2, true, MIGRATION_1_2).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM raw_observation").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT state FROM coverage_gap WHERE id = 1").use {
                it.moveToFirst()
                assertEquals("CLOSED", it.getString(0))
            }
        }
    }

    @Test
    fun migrationTwoToThreePreservesKernelRowsAndCreatesEmptyDiscoveryTables() {
        val name = "migration-2-3.db"
        migrationHelper.createDatabase(name, 2).apply {
            execSQL(
                "INSERT INTO raw_observation(id, source, source_key, user_handle, package_name, post_time_ms, received_at_ms, title, body, content_hash, capture_path, parse_state, duplicate_count, created_at_ms) " +
                    "VALUES(1, 'NOTIFICATION', '0:key', 0, 'com.example.pay', 1000, 1001, '支付', '10元', 'h', 'LIVE_CALLBACK', 'PARSED', 0, 1001)"
            )
            execSQL(
                "INSERT INTO observation_revision(id, observation_id, revision_hash, title, body, revised_at_ms) " +
                    "VALUES(1, 1, 'h', '支付', '10元', 1001)"
            )
            execSQL(
                "INSERT INTO canonical_transaction(id, strong_id_hash, type, status, amount_cents, currency, merchant_hint, occurred_at_ms, backfilled_from, created_at_ms) " +
                    "VALUES(1, NULL, 'PAYMENT', 'DETECTED', 1000, 'CNY', NULL, 1000, NULL, 1001)"
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 3, true, MIGRATION_2_3).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM raw_observation").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM observation_revision").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM canonical_transaction").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM debt_account").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM account_discovery_scan").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test
    fun debtDiscoveryKeepsRevisionAuditAndSecondDrainIsIdempotent() {
        val first = db.observationDao().ingest(
            observation("花呗本期应还 100.00 元，还款日为每月10日", "debt-bill")
        )
        assertEquals(1, DebtAccountDiscoverer.drain(db, ::sha256Hex))

        db.observationDao().ingest(
            observation("花呗还款成功，已成功还款 100.00 元", "debt-repayment")
        )
        assertEquals(1, DebtAccountDiscoverer.drain(db, ::sha256Hex))
        assertEquals(0, DebtAccountDiscoverer.drain(db, ::sha256Hex))

        assertEquals(1L, db.debtAccountDao().countAll())
        assertEquals(2L, db.debtAccountDao().countEvidenceHistoryForObservation(first.id))
        assertEquals(2L, db.debtAccountDao().countCurrentScans())
        assertEquals(
            DebtEventKind.BILL_NOTICE,
            db.debtAccountDao().findCurrentEvidenceForRevision(first.id, "debt-bill")?.eventKind,
        )
        assertEquals(
            DebtEventKind.REPAYMENT,
            db.debtAccountDao().findCurrentEvidenceForRevision(first.id, "debt-repayment")?.eventKind,
        )
    }

    @Test
    fun sameAmountDifferentCardTailsRemainDifferentDebtCandidates() {
        db.observationDao().ingest(
            observation("招商银行信用卡尾号1234消费10.00元", "tail-1234")
                .copy(source = ObservationSource.SMS, sourceKey = "sms:1", packageName = "95555")
        )
        db.observationDao().ingest(
            observation("招商银行信用卡尾号5678消费10.00元", "tail-5678")
                .copy(source = ObservationSource.SMS, sourceKey = "sms:2", packageName = "95555")
        )
        assertEquals(2, DebtAccountDiscoverer.drain(db, ::sha256Hex))
        assertEquals(2L, db.debtAccountDao().countAll())
        assertEquals(2L, db.debtAccountDao().countByStatus(DebtAccountStatus.SUSPECTED))
    }

    @Test
    fun unverifiedBillImportNeverCreatesDebtBaseline() {
        db.observationDao().ingest(
            observation("账单账户 ABCDEF123456，花呗全部待还 8000.00 元", "bill-import")
                .copy(
                    source = ObservationSource.BILL_IMPORT,
                    sourceKey = "bill:1",
                    packageName = "selected-document",
                )
        )
        DebtAccountDiscoverer.drain(db, ::sha256Hex)
        assertEquals(0L, db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED))
        assertEquals(1L, db.debtAccountDao().countByStatus(DebtAccountStatus.SUSPECTED))
    }

    @Test
    fun creditLimitMarketingCreatesNoDebtAccount() {
        db.observationDao().ingest(
            observation("花呗额度提升，最高可用额度20000元，立即领取", "marketing")
        )
        DebtAccountDiscoverer.drain(db, ::sha256Hex)
        assertEquals(0L, db.debtAccountDao().countAll())
        assertEquals(0L, db.debtAccountDao().countPendingDiscovery(DEBT_DISCOVERY_PARSER_VERSION))
    }
}
