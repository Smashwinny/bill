package com.hulk.pillsapp.ledger

import android.content.Context
import androidx.room.Room
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
                report.writeText(
                    "result=PASS\n" +
                        "tests=revision_one_candidate,duplicate_counter,gap_close\n" +
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
}
