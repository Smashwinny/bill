package com.hulk.pillsapp.ledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LedgerDatabaseInstrumentedTest {
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
}
