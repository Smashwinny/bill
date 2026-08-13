package com.hulk.pillsapp.ledger

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hulk.pillsapp.sha256Hex
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun migrationThreeToFourPreservesRowsAndCreatesEmptyStatementTables() {
        val name = "migration-3-4.db"
        migrationHelper.createDatabase(name, 3).apply {
            execSQL(
                "INSERT INTO raw_observation(id, source, source_key, user_handle, package_name, post_time_ms, received_at_ms, title, body, content_hash, capture_path, parse_state, duplicate_count, created_at_ms) " +
                    "VALUES(1, 'NOTIFICATION', '0:key', 0, 'com.example.pay', 1000, 1001, '支付', '10元', 'h', 'LIVE_CALLBACK', 'PARSED', 0, 1001)"
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM raw_observation").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            listOf(
                "statement_import", "statement_artifact_chunk", "statement_row", "statement_import_row"
            ).forEach { table ->
                migrated.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test
    fun migrationFourToFivePreservesRowsAndCreatesEmptyBehaviorTables() {
        val name = "migration-4-5.db"
        migrationHelper.createDatabase(name, 4).apply {
            execSQL(
                "INSERT INTO raw_observation(id, source, source_key, user_handle, package_name, post_time_ms, received_at_ms, title, body, content_hash, capture_path, parse_state, duplicate_count, created_at_ms) " +
                    "VALUES(1, 'NOTIFICATION', '0:key', 0, 'com.example.pay', 1000, 1001, '支付', '10元', 'h', 'LIVE_CALLBACK', 'PARSED', 0, 1001)"
            )
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 5, true, MIGRATION_4_5).use { migrated ->
            migrated.query("SELECT COUNT(*) FROM raw_observation").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
            listOf(
                "behavior_signal_receipt",
                "behavior_template",
                "behavior_candidate",
                "behavior_decision",
            ).forEach { table ->
                migrated.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(0, it.getInt(0))
                }
            }
        }
    }

    @Test
    fun identicalAmountIndependentBehaviorClipsRemainSeparateAndTransitionIsIdempotent() {
        val now = 1000L
        db.behaviorDao().insertTemplateIgnore(
            BehaviorTemplateEntity(
                templateKey = "template",
                packageName = "com.example.pay",
                kind = BehaviorKind.PAYMENT,
                routeSignature = "route",
                appVersionCode = 1,
                positiveCount = 0,
                negativeCount = 0,
                consecutivePositiveCount = 0,
                autoEnabled = false,
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        fun addClip(sourceKey: String): BehaviorCandidateEntity {
            val observationId = db.observationDao().ingest(
                observation("支付成功 10.00元", sourceKey).copy(
                    source = ObservationSource.A11Y,
                    sourceKey = sourceKey,
                    capturePath = CapturePath.A11Y,
                    parseState = ParseState.PARSED,
                )
            ).id
            val canonicalId = db.canonicalDao().createCandidateWithEvidence(
                CanonicalTransactionEntity(
                    strongIdHash = null,
                    type = TxType.PAYMENT,
                    status = TxStatus.DETECTED,
                    amountCents = 1000,
                    merchantHint = null,
                    occurredAtMs = now,
                    backfilledFrom = null,
                    createdAtMs = now,
                ),
                observationId,
                "A11Y_BEHAVIOR_CLIP",
                now,
            )
            val candidate = BehaviorCandidateEntity(
                publicId = sourceKey,
                observationId = observationId,
                canonicalTxId = canonicalId,
                templateKey = "template",
                packageName = "com.example.pay",
                kind = BehaviorKind.PAYMENT,
                amountCents = 1000,
                occurredAtMs = now,
                confidence = 95,
                consumedIntent = true,
                routeSignature = "route",
                appVersionCode = 1,
                ambiguousRepeatCount = 0,
                featureSummary = "safe",
                purpose = null,
                state = BehaviorCandidateState.PENDING,
                createdAtMs = now,
                updatedAtMs = now,
                decidedAtMs = null,
            )
            return candidate.copy(id = db.behaviorDao().insertCandidateIgnore(candidate))
        }
        val first = addClip("a11y-one")
        val second = addClip("a11y-two")
        assertEquals(2L, db.canonicalDao().countAll())
        assertEquals(2L, db.behaviorDao().countByState(BehaviorCandidateState.PENDING))
        val accepted = BehaviorDecisionEngine.apply(
            db, first.id, BehaviorDecision.CONFIRM_PAYMENT, 1000, "午餐", now + 1,
        )
        val repeated = BehaviorDecisionEngine.apply(
            db, first.id, BehaviorDecision.CONFIRM_PAYMENT, 1000, "午餐", now + 2,
        )
        assertTrue(accepted.changed)
        assertFalse(repeated.changed)
        assertEquals(TxStatus.SUCCESS, db.canonicalDao().findById(first.canonicalTxId)?.status)
        assertEquals("午餐", db.canonicalDao().findById(first.canonicalTxId)?.merchantHint)
        assertEquals(1L, db.behaviorDao().countDecisions())
        assertEquals(1, db.behaviorDao().findTemplate("template")?.positiveCount)
        assertEquals(1, db.behaviorDao().incrementAmbiguousRepeat(first.observationId, now + 3))
        assertEquals(1L, db.behaviorDao().findCandidate(first.id)?.ambiguousRepeatCount)
        db.behaviorDao().findTemplate("template")?.let {
            db.behaviorDao().updateTemplate(it.copy(autoEnabled = true, consecutivePositiveCount = 5))
        }
        assertEquals(1, db.behaviorDao().suspendTemplateForAmbiguity("template", now + 4))
        assertFalse(db.behaviorDao().findTemplate("template")!!.autoEnabled)
        assertEquals(BehaviorCandidateState.PENDING, db.behaviorDao().findCandidate(second.id)?.state)
        assertEquals(0L, db.behaviorDao().countOrphans())
    }

    @Test
    fun behaviorOutboxIsEncryptedRoundTrippableAndRemovable() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = java.io.File(context.cacheDir, "behavior-outbox-${java.util.UUID.randomUUID()}")
        val outbox = BehaviorSignalOutbox(directory)
        val signal = BehaviorSignal(
            occurrenceId = "outbox-occurrence",
            clipId = "outbox-clip",
            packageName = "com.example.pay",
            kind = BehaviorKind.REFUND,
            amountCents = 888,
            occurredAtMs = 1234,
            templateKey = "template",
            confidence = 95,
            consumedIntent = true,
            routeSignature = "route",
            appVersionCode = 1,
            ambiguousRepeat = false,
            featureSummary = "safe",
        )
        val file = outbox.stage(signal)
        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("outbox-clip"))
        assertEquals(signal, outbox.pending().single().signal)
        outbox.complete(file)
        assertTrue(outbox.pending().isEmpty())
        directory.delete()
    }

    @Test
    fun behaviorOccurrenceReceiptMakesReplaySideEffectsIdempotent() {
        val now = 2000L
        val observationId = db.observationDao().ingest(
            RawObservationEntity(
                source = ObservationSource.A11Y,
                sourceKey = "receipt-clip",
                userHandle = 0,
                packageName = "com.example.pay",
                postTimeMs = now,
                receivedAtMs = now,
                title = "行为识别：支付成功",
                body = "脱敏摘要",
                contentHash = "receipt-hash",
                capturePath = CapturePath.A11Y,
                parseState = ParseState.PARSED,
                createdAtMs = now,
            )
        ).id
        val receipt = BehaviorSignalReceiptEntity(
            occurrenceId = "fixed-occurrence",
            observationId = observationId,
            ambiguousRepeat = true,
            appliedAtMs = now,
        )
        assertTrue(db.behaviorDao().insertSignalReceiptIgnore(receipt) != -1L)
        assertEquals(-1L, db.behaviorDao().insertSignalReceiptIgnore(receipt))
        assertEquals(receipt, db.behaviorDao().findSignalReceipt(receipt.occurrenceId))
        assertEquals(1L, db.behaviorDao().countSignalReceipts())
        assertEquals(0L, db.behaviorDao().countOrphanSignalReceipts())
    }

    @Test
    fun statementImportIsIdempotentPerParserButNeverMergesRowOccurrences() {
        val now = 1000L
        fun imported(hash: String, publicId: String) = StatementImportEntity(
            publicId = publicId,
            fileHash = hash,
            displayName = "statement.csv",
            sourceKind = StatementSourceKind.WECHAT,
            format = StatementFormat.WECHAT_CSV,
            parserVersion = STATEMENT_PARSER_VERSION,
            authority = StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED,
            status = StatementImportStatus.IMPORTING,
            observedRowFromMs = now,
            observedRowToMs = now,
            rawRowCount = 1,
            validRowCount = 1,
            invalidRowCount = 0,
            ignoredFooterRowCount = 0,
            duplicateRowCount = 0,
            artifactSizeBytes = 0,
            artifactChunkCount = 0,
            importedAtMs = now,
        )
        val row = StatementRowEntity(
            sourceKind = StatementSourceKind.WECHAT,
            rowFingerprint = "row-fingerprint",
            externalIdHash = "external-id-hash",
            occurredAtMs = now,
            amountCents = 1000,
            currency = "CNY",
            direction = StatementDirection.OUT,
            txType = TxType.PAYMENT,
            txStatus = "支付成功",
            counterparty = "测试商户",
            itemDescription = "测试商品",
            rawRecord = "encrypted-db-only",
            createdAtMs = now,
        )
        fun complete(entity: StatementImportEntity, rows: List<Pair<Int, StatementRowEntity>>): StatementPrepareResult {
            val prepared = db.statementDao().prepareImport(entity)
            if (!prepared.duplicateCompleted) {
                if (entity.artifactChunkCount == 1) {
                    val chunk = StatementArtifactChunkEntity(prepared.importId, 0, "chunk-hash", byteArrayOf(1, 2, 3))
                    db.statementDao().appendArtifactChunk(chunk)
                    db.statementDao().appendArtifactChunk(chunk)
                }
                db.statementDao().appendRowBatch(prepared.importId, rows)
                db.statementDao().finalizeImport(prepared.importId, entity.artifactChunkCount, rows.size)
            }
            return prepared
        }
        val twoRows = imported("file-one", "import-one").copy(
            rawRowCount = 2,
            validRowCount = 2,
            artifactSizeBytes = 3,
            artifactChunkCount = 1,
        )
        val first = complete(twoRows, listOf(2 to row, 3 to row))
        val sameFile = complete(twoRows.copy(publicId = "import-two"), listOf(2 to row))
        val overlap = complete(imported("file-two", "import-three"), listOf(2 to row))
        val parserUpgrade = complete(
            imported("file-one", "import-four").copy(parserVersion = STATEMENT_PARSER_VERSION + 1),
            listOf(2 to row.copy(txStatus = "退款成功")),
        )
        val incomplete = db.statementDao().prepareImport(imported("file-incomplete", "import-five"))
        db.statementDao().appendRowBatch(incomplete.importId, listOf(2 to row))

        assertFalse(first.duplicateCompleted)
        assertTrue(sameFile.duplicateCompleted)
        assertFalse(overlap.duplicateCompleted)
        assertFalse(parserUpgrade.duplicateCompleted)
        assertEquals(4L, db.statementDao().countImports())
        assertEquals(4L, db.statementDao().countRows())
        assertEquals(1L, db.statementDao().countIncompleteImports())
        assertEquals(0L, db.statementDao().countCompletedIntegrityFailures())
        assertEquals(0L, db.statementDao().countOrphanLinks())
        assertEquals(0L, db.canonicalDao().countAll())
        assertEquals(0L, db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED))
    }

    @Test
    fun interruptedImportResumesIdempotentlyAndRejectsChangedSourceRow() {
        val base = StatementImportEntity(
            publicId = "resume-one",
            fileHash = "resume-file",
            displayName = "resume.csv",
            sourceKind = StatementSourceKind.WECHAT,
            format = StatementFormat.WECHAT_CSV,
            parserVersion = STATEMENT_PARSER_VERSION,
            authority = StatementAuthority.FORMAT_RECOGNIZED_UNVERIFIED,
            status = StatementImportStatus.IMPORTING,
            observedRowFromMs = 1000,
            observedRowToMs = 2000,
            rawRowCount = 2,
            validRowCount = 2,
            invalidRowCount = 0,
            ignoredFooterRowCount = 0,
            duplicateRowCount = 0,
            artifactSizeBytes = 3,
            artifactChunkCount = 1,
            importedAtMs = 1000,
        )
        fun row(fingerprint: String, at: Long) = StatementRowEntity(
            sourceKind = StatementSourceKind.WECHAT,
            rowFingerprint = fingerprint,
            externalIdHash = null,
            occurredAtMs = at,
            amountCents = 1000,
            currency = "CNY",
            direction = StatementDirection.OUT,
            txType = TxType.PAYMENT,
            txStatus = "支付成功",
            counterparty = null,
            itemDescription = null,
            rawRecord = fingerprint,
            createdAtMs = at,
        )
        val dao = db.statementDao()
        val first = dao.prepareImport(base)
        val chunk = StatementArtifactChunkEntity(first.importId, 0, "chunk", byteArrayOf(1, 2, 3))
        dao.appendArtifactChunk(chunk)
        dao.appendRowBatch(first.importId, listOf(2 to row("row-2", 1000)))
        assertEquals(1, dao.markFailed(first.importId))

        val resumed = dao.prepareImport(base.copy(publicId = "resume-two"))
        assertEquals(first.importId, resumed.importId)
        assertFalse(resumed.duplicateCompleted)
        dao.appendArtifactChunk(chunk)
        val replay = dao.appendRowBatch(first.importId, listOf(2 to row("row-2", 1000)))
        assertEquals(1, replay.existingRows)
        val conflict = runCatching {
            dao.appendRowBatch(first.importId, listOf(2 to row("changed-row-2", 1000)))
        }
        assertTrue(conflict.isFailure)
        dao.appendRowBatch(first.importId, listOf(3 to row("row-3", 2000)))
        dao.finalizeImport(first.importId, 1, 2)

        assertEquals(0L, dao.countIncompleteImports())
        assertEquals(2L, dao.countRows())
        assertEquals(0L, dao.countCompletedIntegrityFailures())
    }
}
