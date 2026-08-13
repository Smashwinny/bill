package com.hulk.pillsapp.ledger

import android.content.Context
import androidx.room.Room
import com.hulk.pillsapp.sha256Hex
import java.io.File

/** Debug APK 本机自检；只使用独立临时库，报告不含任何用户数据。 */
object DebugLedgerSelfTest {
    private const val DB_NAME = "debug_ledger_self_test.db"

    fun run(context: Context) {
        val report = File(context.filesDir, "debug_ledger_self_test.txt")
        try {
            context.deleteDatabase(DB_NAME)
            val db = Room.databaseBuilder(context, LedgerDatabase::class.java, DB_NAME)
                .allowMainThreadQueries()
                .build()
            try {
                revisionKeepsOneCandidate(db)
                duplicateOnlyIncrementsCounter(db)
                closingGapLeavesNoActiveGap(db)
                debtDiscoveryIsAuditableAndIdempotent(db)
                report.writeText(
                    "result=PASS\n" +
                        "tests=revision_one_candidate,duplicate_counter,gap_close,debt_discovery_audit,debt_discovery_idempotence,no_false_baseline\n" +
                        "at_ms=${System.currentTimeMillis()}\n"
                )
            } finally {
                db.close()
                context.deleteDatabase(DB_NAME)
            }
        } catch (failure: Throwable) {
            report.writeText(
                "result=FAIL\n" +
                    "type=${failure.javaClass.name}\n" +
                    "at_ms=${System.currentTimeMillis()}\n"
            )
        }
    }

    private fun observation(body: String, hash: String) = RawObservationEntity(
        source = ObservationSource.NOTIFICATION,
        sourceKey = "0:self-test-key",
        userHandle = 0,
        packageName = "com.example.selftest",
        postTimeMs = 1000,
        receivedAtMs = 1100,
        title = "支付提醒",
        body = body,
        contentHash = hash,
        capturePath = CapturePath.LIVE_CALLBACK,
        parseState = ParseState.PENDING_PARSE,
        createdAtMs = 1100,
    )

    private fun revisionKeepsOneCandidate(db: LedgerDatabase) {
        val first = db.observationDao().ingest(observation("正在支付 10.00 元", "processing"))
        CandidatePromoter.process(db, first.id)
        db.observationDao().ingest(observation("支付成功 10.00 元", "success"))
        CandidatePromoter.process(db, first.id)
        check(db.observationDao().countAll() == 1L)
        check(db.observationDao().countRevisions(first.id) == 2L)
        check(db.canonicalDao().countAll() == 1L)
        check(db.canonicalDao().countEvidenceForObservation(first.id) == 1L)
    }

    private fun duplicateOnlyIncrementsCounter(db: LedgerDatabase) {
        val separate = observation("支付成功 20.00 元", "duplicate").copy(sourceKey = "0:duplicate-key")
        val first = db.observationDao().ingest(separate)
        db.observationDao().ingest(separate)
        check(db.observationDao().findById(first.id)?.duplicateCount == 1L)
        check(db.observationDao().countRevisions(first.id) == 1L)
    }

    private fun closingGapLeavesNoActiveGap(db: LedgerDatabase) {
        val dao = db.coverageGapDao()
        dao.insert(
            CoverageGapEntity(
                detector = "SELF_TEST",
                startedAtMs = 1000,
                endedAtMs = null,
                state = GapState.ACTIVE,
                note = null,
            )
        )
        dao.closeOpenByDetector("SELF_TEST", 2000)
        check(dao.countOpenByDetector("SELF_TEST") == 0L)
    }

    private fun debtDiscoveryIsAuditableAndIdempotent(db: LedgerDatabase) {
        val dao = db.observationDao()
        val debt = observation("花呗本期应还 100.00 元，还款日为每月10日", "debt-bill")
            .copy(sourceKey = "0:debt-self-test")
        val first = dao.ingest(debt)
        dao.ingest(debt.copy(body = "花呗还款成功，已成功还款 100.00 元", contentHash = "debt-paid"))
        dao.ingest(
            observation("花呗额度提升，最高可用额度20000元，立即领取", "debt-marketing")
                .copy(sourceKey = "0:debt-marketing")
        )

        DebtAccountDiscoverer.drain(db, ::sha256Hex)
        check(DebtAccountDiscoverer.drain(db, ::sha256Hex) == 0)
        check(db.debtAccountDao().countAll() == 1L)
        check(db.debtAccountDao().countByStatus(DebtAccountStatus.SUSPECTED) == 1L)
        check(db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED) == 0L)
        check(db.debtAccountDao().countEvidenceHistoryForObservation(first.id) == 2L)
        check(db.debtAccountDao().countRepaymentsAwaitingBaseline() == 1L)
        check(db.debtAccountDao().countPendingDiscovery(DEBT_DISCOVERY_PARSER_VERSION) == 0L)
        check(db.debtAccountDao().countFailedScans() == 0L)
        check(db.debtAccountDao().countOrphanEvidence() == 0L)
        check(db.debtAccountDao().countDuplicateSignalFingerprints() == 0L)
        check(db.debtAccountDao().countDuplicateConfirmedIdentities() == 0L)
        check(
            db.debtAccountDao().countStatusWithoutAuthoritativeEvidence(
                DebtAccountStatus.BASELINED,
            ) == 0L
        )
    }
}
