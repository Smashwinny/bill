package com.hulk.pillsapp.ledger

import android.content.Context
import androidx.room.Room
import com.hulk.pillsapp.sha256Hex
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
                parserUpgradeRetiresWrongTailWithoutDeletingAudit(db)
                statementXlsxParserWorksOnDevice()
                statementArtifactChunkBoundaryWorksOnDevice()
                statementImportIsAuditableAndDoesNotPostLedger(db)
                report.writeText(
                    "result=PASS\n" +
                        "tests=revision_one_candidate,duplicate_counter,gap_close,debt_discovery_audit,debt_discovery_idempotence,no_false_baseline,parser_upgrade_retires_wrong_tail,statement_xlsx_android,statement_artifact_256k,statement_import_idempotence,statement_parser_reparse,no_row_occurrence_merge,no_import_auto_posting\n" +
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

    private fun parserUpgradeRetiresWrongTailWithoutDeletingAudit(db: LedgerDatabase) {
        val now = System.currentTimeMillis()
        val raw = observation("信用卡2026年账单，本期应还100.00元", "upgrade-tail")
            .copy(sourceKey = "0:upgrade-tail", packageName = "95555")
        val observationId = db.observationDao().ingest(raw).id
        val oldAccountId = db.debtAccountDao().insertAccountIgnore(
            DebtAccountEntity(
                publicId = "self-test-old-tail",
                clusterHash = "old-tail-cluster",
                identityHash = null,
                product = DebtProduct.CREDIT_CARD,
                institutionCode = "CMB",
                institutionLabel = "招商银行",
                displayLabel = "招商银行信用卡 ••2026",
                maskedSuffix = "2026",
                userHandle = 0,
                status = DebtAccountStatus.SUSPECTED,
                confidence = 45,
                lastEventKind = DebtEventKind.BILL_NOTICE,
                lastEvidenceStrength = DebtEvidenceStrength.OBSERVATIONAL,
                dueDayOfMonth = null,
                firstSeenAtMs = now,
                lastSeenAtMs = now,
                createdAtMs = now,
                updatedAtMs = now,
            )
        )
        db.debtAccountDao().insertEvidence(
            DebtAccountEvidenceEntity(
                observationId = observationId,
                accountId = oldAccountId,
                contentHash = "upgrade-tail",
                parserVersion = 1,
                signalFingerprint = "old-tail-signal",
                isCurrent = true,
                eventKind = DebtEventKind.BILL_NOTICE,
                strength = DebtEvidenceStrength.OBSERVATIONAL,
                amountRole = DebtAmountRole.CURRENT_DUE,
                amountCents = 10_000,
                dueDayOfMonth = null,
                observedAtMs = now,
                createdAtMs = now,
            )
        )
        db.debtAccountDao().insertScan(
            AccountDiscoveryScanEntity(
                observationId = observationId,
                contentHash = "upgrade-tail",
                parserVersion = 1,
                isCurrent = true,
                result = DiscoveryScanResult.MATCHED,
                scannedAtMs = now,
            )
        )

        check(DebtAccountDiscoverer.drain(db, ::sha256Hex) > 0)
        check(db.debtAccountDao().findById(oldAccountId)?.status == DebtAccountStatus.DORMANT)
        check(db.debtAccountDao().countEvidenceHistoryForObservation(observationId) == 2L)
        check(db.debtAccountDao().listVisible().none { it.id == oldAccountId })
    }

    private fun statementImportIsAuditableAndDoesNotPostLedger(db: LedgerDatabase) {
        val canonicalBefore = db.canonicalDao().countAll()
        val baselineBefore = db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED)
        val csv = """
            #微信支付账单明细
            交易时间,交易类型,交易对方,商品,收/支,金额(元),当前状态,交易单号
            2026-08-01 12:30:00,商户消费,自检商户,自检商品,支出,10.00,支付成功,SELF-WX-1
            2026-08-01 12:30:00,商户消费,自检商户,自检商品,支出,10.00,支付成功,SELF-WX-1
        """.trimIndent()
        val preview = StatementFileParser.parse("self-test-wechat.csv", csv.toByteArray())
        check(preview.canImport)
        val now = System.currentTimeMillis()
        fun completeImport(entity: StatementImportEntity, rows: List<Pair<Int, StatementRowEntity>>): StatementPrepareResult {
            val prepared = db.statementDao().prepareImport(
                entity.copy(
                    rawRowCount = rows.size,
                    validRowCount = rows.size,
                    artifactSizeBytes = 0,
                    artifactChunkCount = 0,
                )
            )
            if (!prepared.duplicateCompleted) {
                db.statementDao().appendRowBatch(prepared.importId, rows)
                db.statementDao().finalizeImport(prepared.importId, 0, rows.size)
            }
            return prepared
        }
        val first = completeImport(preview.toImportEntity(now), preview.toRowEntities(now))
        val second = completeImport(preview.toImportEntity(now + 1), preview.toRowEntities(now + 1))
        val parserUpgrade = completeImport(
            preview.toImportEntity(now + 2).copy(parserVersion = STATEMENT_PARSER_VERSION + 1),
            preview.toRowEntities(now + 2),
        )
        check(!first.duplicateCompleted)
        check(second.duplicateCompleted)
        check(!parserUpgrade.duplicateCompleted)
        check(db.statementDao().countImports() == 2L)
        check(db.statementDao().countRows() == 4L)
        check(db.statementDao().countAwaitingValidation() == 2L)
        check(db.statementDao().countCompletedIntegrityFailures() == 0L)
        check(db.statementDao().countOrphanLinks() == 0L)
        check(db.canonicalDao().countAll() == canonicalBefore)
        check(db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED) == baselineBefore)
    }

    private fun statementXlsxParserWorksOnDevice() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            entry("xl/workbook.xml", "<workbook/>")
            entry(
                "xl/sharedStrings.xml",
                "<sst><si><t>微信支付账单明细</t></si><si><t>交易时间</t></si><si><t>交易单号</t></si>" +
                    "<si><t>当前状态</t></si><si><t>金额(元)</t></si><si><t>收/支</t></si>" +
                    "<si><t>SELF-XLSX</t></si><si><t>支付成功</t></si><si><t>支出</t></si></sst>",
            )
            entry(
                "xl/worksheets/sheet1.xml",
                "<worksheet><sheetData>" +
                    "<row><c r=\"A1\" t=\"s\"><v>0</v></c></row>" +
                    "<row><c r=\"A2\" t=\"s\"><v>1</v></c><c r=\"B2\" t=\"s\"><v>2</v></c>" +
                    "<c r=\"C2\" t=\"s\"><v>3</v></c><c r=\"D2\" t=\"s\"><v>4</v></c>" +
                    "<c r=\"E2\" t=\"s\"><v>5</v></c></row>" +
                    "<row><c r=\"A3\"><v>46235.5</v></c><c r=\"B3\" t=\"s\"><v>6</v></c>" +
                    "<c r=\"C3\" t=\"s\"><v>7</v></c><c r=\"D3\"><v>8.88</v></c>" +
                    "<c r=\"E3\" t=\"s\"><v>8</v></c></row>" +
                    "</sheetData></worksheet>",
            )
        }
        val preview = StatementFileParser.parse("self-test.xlsx", output.toByteArray())
        check(preview.canImport)
        check(preview.rows.single().amountCents == 888L)
    }

    private fun statementArtifactChunkBoundaryWorksOnDevice() {
        val bytes = ByteArray(STATEMENT_ARTIFACT_CHUNK_BYTES + 1) { index -> (index % 251).toByte() }
        val hashes = StatementFileParser.artifactChunkHashes(bytes)
        check(hashes.size == 2)
        check(hashes.all { it.length == 64 })
        check(hashes[0] != hashes[1])
    }
}
