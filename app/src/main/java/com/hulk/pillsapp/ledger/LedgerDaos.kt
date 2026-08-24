package com.hulk.pillsapp.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

/** 同步落盘的判定结果；id 均为 raw_observation.id。 */
sealed interface IngestOutcome {
    val id: Long

    data class New(override val id: Long) : IngestOutcome
    data class Duplicate(override val id: Long) : IngestOutcome
    data class Revised(override val id: Long) : IngestOutcome
}

/** observation_revision 与其来源元数据的只读投影，保证每个通知修订都独立接受发现审计。 */
data class DebtDiscoveryInput(
    @androidx.room.ColumnInfo(name = "observation_id") val observationId: Long,
    val source: ObservationSource,
    @androidx.room.ColumnInfo(name = "user_handle") val userHandle: Int,
    @androidx.room.ColumnInfo(name = "package_name") val packageName: String,
    @androidx.room.ColumnInfo(name = "post_time_ms") val postTimeMs: Long,
    @androidx.room.ColumnInfo(name = "content_hash") val contentHash: String,
    val title: String,
    val body: String,
    @androidx.room.ColumnInfo(name = "revision_at_ms") val revisionAtMs: Long,
)

data class RawSourceCoverageSummary(
    val source: ObservationSource,
    @androidx.room.ColumnInfo(name = "user_handle") val userHandle: Int,
    @androidx.room.ColumnInfo(name = "source_namespace") val sourceNamespace: String,
    @androidx.room.ColumnInfo(name = "observation_count") val observationCount: Long,
    @androidx.room.ColumnInfo(name = "first_seen_at_ms") val firstSeenAtMs: Long,
    @androidx.room.ColumnInfo(name = "last_seen_at_ms") val lastSeenAtMs: Long,
    @androidx.room.ColumnInfo(name = "transaction_evidence_count") val transactionEvidenceCount: Long,
    @androidx.room.ColumnInfo(name = "debt_evidence_count") val debtEvidenceCount: Long,
)

data class StatementImportSummary(
    val id: Long,
    @androidx.room.ColumnInfo(name = "display_name") val displayName: String,
    @androidx.room.ColumnInfo(name = "source_kind") val sourceKind: StatementSourceKind,
    val format: StatementFormat,
    val authority: StatementAuthority,
    val status: StatementImportStatus,
    @androidx.room.ColumnInfo(name = "observed_row_from_ms") val observedRowFromMs: Long,
    @androidx.room.ColumnInfo(name = "observed_row_to_ms") val observedRowToMs: Long,
    @androidx.room.ColumnInfo(name = "valid_row_count") val validRowCount: Int,
    @androidx.room.ColumnInfo(name = "invalid_row_count") val invalidRowCount: Int,
    @androidx.room.ColumnInfo(name = "ignored_footer_row_count") val ignoredFooterRowCount: Int,
    @androidx.room.ColumnInfo(name = "duplicate_row_count") val duplicateRowCount: Int,
    @androidx.room.ColumnInfo(name = "linked_row_count") val linkedRowCount: Long,
    @androidx.room.ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
)

@Dao
abstract class ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertIgnore(observation: RawObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertRevisionIgnore(revision: ObservationRevisionEntity): Long

    @Query("SELECT * FROM raw_observation WHERE source = :source AND source_key = :sourceKey LIMIT 1")
    abstract fun findBySourceAndKey(source: ObservationSource, sourceKey: String): RawObservationEntity?

    @Query("SELECT * FROM raw_observation")
    protected abstract fun allForDurableRecovery(): List<RawObservationEntity>

    @Query("SELECT * FROM raw_observation WHERE id = :id")
    abstract fun findById(id: Long): RawObservationEntity?

    @Query("SELECT COALESCE(MAX(received_at_ms), 0) FROM raw_observation")
    abstract fun maxReceivedAtMs(): Long

    @Query("UPDATE raw_observation SET duplicate_count = duplicate_count + 1 WHERE id = :id")
    protected abstract fun incrementDuplicate(id: Long)

    @Query(
        """UPDATE raw_observation
           SET title = :title, body = :body, content_hash = :contentHash,
               received_at_ms = :receivedAtMs, parse_state = 'PENDING_PARSE'
           WHERE id = :id"""
    )
    protected abstract fun applyRevision(
        id: Long,
        title: String,
        body: String,
        contentHash: String,
        receivedAtMs: Long,
    )

    /**
     * 约束级去重入口（V1.1 §4）：
     * - 新 (source, key)：插入新观察；
     * - 同 key 同哈希：重复投递计数 +1，不增行（V1 §10 用例 4）；
     * - 同 key 不同哈希：写入修订并更新最新内容，不增观察行（V1 §10 用例 3）；
     * - 不同 key：永远是独立观察（V1 §10 用例 5）。
     */
    @Transaction
    open fun ingest(observation: RawObservationEntity): IngestOutcome {
        val existing = findBySourceAndKey(observation.source, observation.sourceKey)
        when (decideIngest(existing?.contentHash, observation.contentHash)) {
            IngestDecision.NEW -> {
                val id = insertIgnore(observation)
                // 唯一约束竞争下的兜底：并发已被单线程执行器排除，这里只做防御。
                if (id == -1L) {
                    val raced = findBySourceAndKey(observation.source, observation.sourceKey)
                        ?: return IngestOutcome.Duplicate(-1L)
                    return IngestOutcome.Duplicate(raced.id)
                }
                insertRevisionIgnore(
                    ObservationRevisionEntity(
                        observationId = id,
                        revisionHash = observation.contentHash,
                        title = observation.title,
                        body = observation.body,
                        revisedAtMs = observation.receivedAtMs,
                    )
                )
                return IngestOutcome.New(id)
            }
            IngestDecision.DUPLICATE -> {
                incrementDuplicate(existing!!.id)
                return IngestOutcome.Duplicate(existing.id)
            }
            IngestDecision.REVISION -> {
                insertRevisionIgnore(
                    ObservationRevisionEntity(
                        observationId = existing!!.id,
                        revisionHash = observation.contentHash,
                        title = observation.title,
                        body = observation.body,
                        revisedAtMs = observation.receivedAtMs,
                    )
                )
                applyRevision(
                    id = existing.id,
                    title = observation.title,
                    body = observation.body,
                    contentHash = observation.contentHash,
                    receivedAtMs = observation.receivedAtMs,
                )
                return IngestOutcome.Revised(existing.id)
            }
        }
    }

    /**
     * fsync outbox 重放入口。若数据库已经提交相同内容，或当前内容的接收时间更新，说明
     * 文件只是“提交后未删除”的残留/旧修订，不增加 duplicate_count，也不回滚新修订。
     */
    @Transaction
    open fun ingestDurableCallback(observation: RawObservationEntity): IngestOutcome {
        val existing = findBySourceAndKey(observation.source, observation.sourceKey)
        if (existing != null &&
            (existing.contentHash == observation.contentHash ||
                existing.receivedAtMs >= observation.receivedAtMs)
        ) {
            return IngestOutcome.Duplicate(existing.id)
        }
        return ingest(observation)
    }

    /** Load the small identity set once, then replay one bounded outbox batch in order. */
    @Transaction
    open fun ingestDurableBatch(observations: List<RawObservationEntity>): List<IngestOutcome> {
        val existingByIdentity = allForDurableRecovery().associateByTo(LinkedHashMap()) {
            it.source to it.sourceKey
        }
        return observations.map { observation ->
            val identity = observation.source to observation.sourceKey
            val existing = existingByIdentity[identity]
            if (existing != null &&
                (existing.contentHash == observation.contentHash ||
                    existing.receivedAtMs >= observation.receivedAtMs)
            ) {
                return@map IngestOutcome.Duplicate(existing.id)
            }

            when (decideIngest(existing?.contentHash, observation.contentHash)) {
                IngestDecision.NEW -> {
                    val id = insertIgnore(observation)
                    if (id == -1L) {
                        val raced = findBySourceAndKey(observation.source, observation.sourceKey)
                        if (raced != null) existingByIdentity[identity] = raced
                        return@map IngestOutcome.Duplicate(raced?.id ?: -1L)
                    }
                    insertRevisionIgnore(
                        ObservationRevisionEntity(
                            observationId = id,
                            revisionHash = observation.contentHash,
                            title = observation.title,
                            body = observation.body,
                            revisedAtMs = observation.receivedAtMs,
                        )
                    )
                    existingByIdentity[identity] = observation.copy(id = id)
                    IngestOutcome.New(id)
                }

                IngestDecision.DUPLICATE -> IngestOutcome.Duplicate(existing!!.id)

                IngestDecision.REVISION -> {
                    insertRevisionIgnore(
                        ObservationRevisionEntity(
                            observationId = existing!!.id,
                            revisionHash = observation.contentHash,
                            title = observation.title,
                            body = observation.body,
                            revisedAtMs = observation.receivedAtMs,
                        )
                    )
                    applyRevision(
                        id = existing.id,
                        title = observation.title,
                        body = observation.body,
                        contentHash = observation.contentHash,
                        receivedAtMs = observation.receivedAtMs,
                    )
                    existingByIdentity[identity] = existing.copy(
                        title = observation.title,
                        body = observation.body,
                        contentHash = observation.contentHash,
                        receivedAtMs = observation.receivedAtMs,
                        parseState = ParseState.PENDING_PARSE,
                    )
                    IngestOutcome.Revised(existing.id)
                }
            }
        }
    }

    @Query("SELECT * FROM raw_observation WHERE parse_state = 'PENDING_PARSE' ORDER BY id LIMIT :limit")
    abstract fun pendingParse(limit: Int): List<RawObservationEntity>

    @Query("UPDATE raw_observation SET parse_state = :state WHERE id = :id")
    abstract fun updateParseState(id: Long, state: ParseState)

    @Query("SELECT COUNT(*) FROM raw_observation")
    abstract fun countAll(): Long

    @Query("SELECT COUNT(*) FROM raw_observation WHERE parse_state = 'PENDING_PARSE'")
    abstract fun countPendingParse(): Long

    @Query("SELECT COUNT(*) FROM observation_revision WHERE observation_id = :observationId")
    abstract fun countRevisions(observationId: Long): Long

    @Query(
        "SELECT raw_observation.source AS source, raw_observation.user_handle AS user_handle, " +
            "raw_observation.package_name AS source_namespace, " +
            "COUNT(DISTINCT raw_observation.id) AS observation_count, " +
            "MIN(raw_observation.post_time_ms) AS first_seen_at_ms, " +
            "MAX(raw_observation.post_time_ms) AS last_seen_at_ms, " +
            "COUNT(DISTINCT evidence_link.id) AS transaction_evidence_count, " +
            "COUNT(DISTINCT debt_account_evidence.id) AS debt_evidence_count " +
            "FROM raw_observation " +
            "LEFT JOIN evidence_link ON evidence_link.observation_id = raw_observation.id " +
            "LEFT JOIN debt_account_evidence ON debt_account_evidence.observation_id = raw_observation.id " +
            "AND debt_account_evidence.is_current = 1 " +
            "GROUP BY raw_observation.source, raw_observation.user_handle, raw_observation.package_name " +
            "ORDER BY last_seen_at_ms DESC"
    )
    abstract fun sourceCoverageSummaries(): List<RawSourceCoverageSummary>
}

@Dao
abstract class CanonicalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertIgnore(tx: CanonicalTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertEvidenceIgnore(link: EvidenceLinkEntity): Long

    @Query("SELECT * FROM canonical_transaction WHERE strong_id_hash = :hash LIMIT 1")
    abstract fun findByStrongIdHash(hash: String): CanonicalTransactionEntity?

    @Query("SELECT * FROM canonical_transaction WHERE id = :id")
    abstract fun findById(id: Long): CanonicalTransactionEntity?

    @Query("SELECT canonical_transaction.* FROM canonical_transaction INNER JOIN evidence_link ON evidence_link.canonical_tx_id = canonical_transaction.id WHERE evidence_link.observation_id = :observationId LIMIT 1")
    abstract fun findByObservationId(observationId: Long): CanonicalTransactionEntity?

    @Query("UPDATE canonical_transaction SET strong_id_hash = :strongIdHash, type = :type, amount_cents = :amountCents, occurred_at_ms = :occurredAtMs WHERE id = :id")
    abstract fun updateCandidate(
        id: Long,
        strongIdHash: String?,
        type: TxType,
        amountCents: Long?,
        occurredAtMs: Long,
    )

    @Query(
        "UPDATE canonical_transaction SET type = :type, status = :status, " +
            "amount_cents = :amountCents, merchant_hint = :purpose WHERE id = :id"
    )
    abstract fun updateVerification(
        id: Long,
        type: TxType,
        status: TxStatus,
        amountCents: Long?,
        purpose: String?,
    )

    /**
     * 强标识合并（V1 §5.2）：strong_id_hash 冲突时不新增交易，只补证据链接。
     * 返回交易 id；无强标识的候选每次独立成行（V1 §5.1 禁止弱合并）。
     */
    @Transaction
    open fun createCandidateWithEvidence(
        tx: CanonicalTransactionEntity,
        observationId: Long,
        matchReason: String,
        nowMs: Long,
    ): Long {
        val linked = findByObservationId(observationId)
        if (linked != null) {
            updateCandidate(
                id = linked.id,
                strongIdHash = tx.strongIdHash,
                type = tx.type,
                amountCents = tx.amountCents,
                occurredAtMs = tx.occurredAtMs,
            )
            return linked.id
        }
        val insertedId = insertIgnore(tx)
        if (insertedId != -1L) {
            insertEvidenceIgnore(
                EvidenceLinkEntity(
                    observationId = observationId,
                    canonicalTxId = insertedId,
                    matchReason = matchReason,
                    createdAtMs = nowMs,
                )
            )
            return insertedId
        }
        val existing = tx.strongIdHash?.let { findByStrongIdHash(it) }
            ?: error("insertIgnore 冲突但无 strongIdHash，不可能发生")
        insertEvidenceIgnore(
            EvidenceLinkEntity(
                observationId = observationId,
                canonicalTxId = existing.id,
                matchReason = "STRONG_ID_MERGED",
                createdAtMs = nowMs,
            )
        )
        return existing.id
    }

    @Query("SELECT COUNT(*) FROM canonical_transaction")
    abstract fun countAll(): Long

    @Query("SELECT COUNT(*) FROM evidence_link WHERE observation_id = :observationId")
    abstract fun countEvidenceForObservation(observationId: Long): Long
}

data class BehaviorDashboardSnapshot(
    val candidates: List<BehaviorCandidateEntity>,
    val pendingCount: Long,
    val autoRecordedCount: Long,
)

@Dao
abstract class BehaviorDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertSignalReceiptIgnore(receipt: BehaviorSignalReceiptEntity): Long

    @Query("SELECT * FROM behavior_signal_receipt WHERE occurrence_id = :occurrenceId LIMIT 1")
    abstract fun findSignalReceipt(occurrenceId: String): BehaviorSignalReceiptEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertCandidateIgnore(candidate: BehaviorCandidateEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertTemplateIgnore(template: BehaviorTemplateEntity): Long

    @Update
    abstract fun updateTemplate(template: BehaviorTemplateEntity)

    @Insert
    abstract fun insertDecision(decision: BehaviorDecisionEntity): Long

    @Query("SELECT * FROM behavior_candidate WHERE id = :id")
    abstract fun findCandidate(id: Long): BehaviorCandidateEntity?

    @Query("SELECT * FROM behavior_candidate WHERE observation_id = :observationId LIMIT 1")
    abstract fun findCandidateByObservation(observationId: Long): BehaviorCandidateEntity?

    @Query(
        "UPDATE behavior_candidate SET ambiguous_repeat_count = ambiguous_repeat_count + 1, " +
            "updated_at_ms = :nowMs WHERE observation_id = :observationId"
    )
    abstract fun incrementAmbiguousRepeat(observationId: Long, nowMs: Long): Int

    @Query(
        "UPDATE behavior_template SET auto_enabled = 0, consecutive_positive_count = 0, " +
            "updated_at_ms = :nowMs WHERE template_key = :templateKey"
    )
    abstract fun suspendTemplateForAmbiguity(templateKey: String, nowMs: Long): Int

    @Query("SELECT * FROM behavior_template WHERE template_key = :templateKey LIMIT 1")
    abstract fun findTemplate(templateKey: String): BehaviorTemplateEntity?

    @Query(
        "UPDATE behavior_candidate SET kind = :kind, amount_cents = :amountCents, " +
            "purpose = :purpose, state = :state, updated_at_ms = :nowMs, decided_at_ms = :nowMs " +
            "WHERE id = :id AND state = :expectedState"
    )
    abstract fun transition(
        id: Long,
        expectedState: BehaviorCandidateState,
        state: BehaviorCandidateState,
        kind: BehaviorKind,
        amountCents: Long?,
        purpose: String?,
        nowMs: Long,
    ): Int

    @Query(
        "SELECT * FROM behavior_candidate WHERE state IN ('PENDING', 'AUTO_RECORDED') ORDER BY " +
            "CASE state WHEN 'PENDING' THEN 0 ELSE 1 END, occurred_at_ms DESC, id DESC LIMIT :limit"
    )
    abstract fun listActionable(limit: Int): List<BehaviorCandidateEntity>

    @Query(
        "SELECT * FROM behavior_candidate WHERE state NOT IN ('PENDING', 'AUTO_RECORDED') " +
            "ORDER BY occurred_at_ms DESC, id DESC LIMIT :limit"
    )
    abstract fun listRecentResolved(limit: Int = 20): List<BehaviorCandidateEntity>

    /** 同一读事务内生成首页快照，避免状态转换恰好跨两次查询时重复展示。 */
    @Transaction
    open fun dashboardSnapshot(
        actionableLimit: Int = 10,
        resolvedLimit: Int = 20,
    ): BehaviorDashboardSnapshot = BehaviorDashboardSnapshot(
        candidates = listActionable(actionableLimit) + listRecentResolved(resolvedLimit),
        pendingCount = countByState(BehaviorCandidateState.PENDING),
        autoRecordedCount = countByState(BehaviorCandidateState.AUTO_RECORDED),
    )

    @Query("SELECT COUNT(*) FROM behavior_candidate WHERE state = :state")
    abstract fun countByState(state: BehaviorCandidateState): Long

    @Query("SELECT COUNT(*) FROM behavior_template WHERE auto_enabled = 1")
    abstract fun countAutoTemplates(): Long

    @Query("SELECT COUNT(*) FROM behavior_decision")
    abstract fun countDecisions(): Long

    @Query("SELECT COUNT(*) FROM behavior_signal_receipt")
    abstract fun countSignalReceipts(): Long

    @Query(
        "SELECT COUNT(*) FROM behavior_signal_receipt LEFT JOIN raw_observation " +
            "ON raw_observation.id = behavior_signal_receipt.observation_id " +
            "WHERE raw_observation.id IS NULL"
    )
    abstract fun countOrphanSignalReceipts(): Long

    @Query(
        "SELECT COUNT(*) FROM behavior_candidate LEFT JOIN raw_observation " +
            "ON raw_observation.id = behavior_candidate.observation_id " +
            "LEFT JOIN canonical_transaction ON canonical_transaction.id = behavior_candidate.canonical_tx_id " +
            "WHERE raw_observation.id IS NULL OR canonical_transaction.id IS NULL"
    )
    abstract fun countOrphans(): Long
}

@Dao
abstract class DebtAccountDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertAccountIgnore(account: DebtAccountEntity): Long

    @Update
    abstract fun updateAccount(account: DebtAccountEntity)

    @Query("SELECT * FROM debt_account WHERE cluster_hash = :clusterHash LIMIT 1")
    abstract fun findByClusterHash(clusterHash: String): DebtAccountEntity?

    @Query("SELECT * FROM debt_account WHERE identity_hash = :identityHash LIMIT 1")
    abstract fun findByIdentityHash(identityHash: String): DebtAccountEntity?

    @Query("SELECT * FROM debt_account WHERE id = :id")
    abstract fun findById(id: Long): DebtAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertEvidence(evidence: DebtAccountEvidenceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insertScan(scan: AccountDiscoveryScanEntity)

    @Query("UPDATE debt_account_evidence SET is_current = 0 WHERE observation_id = :observationId AND content_hash = :contentHash AND is_current = 1")
    abstract fun supersedeEvidenceVersion(observationId: Long, contentHash: String)

    @Query("UPDATE account_discovery_scan SET is_current = 0 WHERE observation_id = :observationId AND content_hash = :contentHash AND is_current = 1")
    abstract fun supersedeScanVersion(observationId: Long, contentHash: String)

    @Query(
        "UPDATE debt_account SET status = 'DORMANT', updated_at_ms = :updatedAtMs " +
            "WHERE status IN ('SUSPECTED', 'IDENTIFIED') " +
            "AND NOT EXISTS (SELECT 1 FROM debt_account_evidence " +
            "WHERE account_id = debt_account.id AND is_current = 1)"
    )
    abstract fun retireUnreferencedCandidates(updatedAtMs: Long): Int

    @Query(
        "SELECT raw_observation.id AS observation_id, raw_observation.source AS source, " +
            "raw_observation.user_handle AS user_handle, raw_observation.package_name AS package_name, " +
            "raw_observation.post_time_ms AS post_time_ms, observation_revision.revision_hash AS content_hash, " +
            "observation_revision.title AS title, observation_revision.body AS body, " +
            "observation_revision.revised_at_ms AS revision_at_ms " +
            "FROM observation_revision INNER JOIN raw_observation " +
            "ON raw_observation.id = observation_revision.observation_id " +
            "LEFT JOIN account_discovery_scan ON account_discovery_scan.observation_id = raw_observation.id " +
            "AND account_discovery_scan.content_hash = observation_revision.revision_hash " +
            "AND account_discovery_scan.is_current = 1 " +
            "WHERE account_discovery_scan.observation_id IS NULL " +
            "OR account_discovery_scan.parser_version < :parserVersion " +
            "ORDER BY raw_observation.id, observation_revision.id LIMIT :limit"
    )
    abstract fun pendingDiscovery(parserVersion: Int, limit: Int): List<DebtDiscoveryInput>

    @Query(
        "SELECT raw_observation.id AS observation_id, raw_observation.source AS source, " +
            "raw_observation.user_handle AS user_handle, raw_observation.package_name AS package_name, " +
            "raw_observation.post_time_ms AS post_time_ms, observation_revision.revision_hash AS content_hash, " +
            "observation_revision.title AS title, observation_revision.body AS body, " +
            "observation_revision.revised_at_ms AS revision_at_ms " +
            "FROM observation_revision INNER JOIN raw_observation " +
            "ON raw_observation.id = observation_revision.observation_id " +
            "LEFT JOIN account_discovery_scan ON account_discovery_scan.observation_id = raw_observation.id " +
            "AND account_discovery_scan.content_hash = observation_revision.revision_hash " +
            "AND account_discovery_scan.is_current = 1 " +
            "WHERE raw_observation.id = :observationId AND (account_discovery_scan.observation_id IS NULL " +
            "OR account_discovery_scan.parser_version < :parserVersion) " +
            "ORDER BY observation_revision.id"
    )
    abstract fun pendingDiscoveryForObservation(
        observationId: Long,
        parserVersion: Int,
    ): List<DebtDiscoveryInput>

    @Query(
        "SELECT COUNT(*) FROM observation_revision " +
            "LEFT JOIN account_discovery_scan ON account_discovery_scan.observation_id = observation_revision.observation_id " +
            "AND account_discovery_scan.content_hash = observation_revision.revision_hash " +
            "AND account_discovery_scan.is_current = 1 " +
            "WHERE account_discovery_scan.observation_id IS NULL " +
            "OR account_discovery_scan.parser_version < :parserVersion"
    )
    abstract fun countPendingDiscovery(parserVersion: Int): Long

    @Query("SELECT COUNT(*) FROM observation_revision")
    abstract fun countEligibleRevisions(): Long

    @Query("SELECT COUNT(*) FROM account_discovery_scan WHERE is_current = 1")
    abstract fun countCurrentScans(): Long

    @Query("SELECT COUNT(*) FROM account_discovery_scan WHERE is_current = 1 AND result = 'FAILED'")
    abstract fun countFailedScans(): Long

    @Query("SELECT COUNT(*) FROM debt_account")
    abstract fun countAll(): Long

    @Query("SELECT COUNT(*) FROM debt_account WHERE status NOT IN ('EXCLUDED', 'DORMANT')")
    abstract fun countVisible(): Long

    @Query("SELECT COUNT(*) FROM debt_account WHERE status = :status")
    abstract fun countByStatus(status: DebtAccountStatus): Long

    @Query("SELECT * FROM debt_account WHERE status NOT IN ('EXCLUDED', 'DORMANT') ORDER BY last_seen_at_ms DESC, id DESC")
    abstract fun listVisible(): List<DebtAccountEntity>

    @Query("SELECT COUNT(*) FROM debt_account_evidence WHERE account_id = :accountId AND is_current = 1")
    abstract fun countCurrentEvidenceForAccount(accountId: Long): Long

    @Query("SELECT COUNT(*) FROM debt_account_evidence WHERE observation_id = :observationId")
    abstract fun countEvidenceHistoryForObservation(observationId: Long): Long

    @Query("SELECT COUNT(*) FROM debt_account_evidence WHERE is_current = 1")
    abstract fun countCurrentEvidence(): Long

    @Query("SELECT COUNT(*) FROM debt_account_evidence LEFT JOIN debt_account ON debt_account.id = debt_account_evidence.account_id WHERE debt_account.id IS NULL")
    abstract fun countOrphanEvidence(): Long

    @Query(
        "SELECT COUNT(*) FROM (SELECT 1 FROM debt_account_evidence " +
            "GROUP BY observation_id, content_hash, parser_version, signal_fingerprint HAVING COUNT(*) > 1)"
    )
    abstract fun countDuplicateSignalFingerprints(): Long

    @Query(
        "SELECT COUNT(*) FROM (SELECT 1 FROM debt_account WHERE identity_hash IS NOT NULL " +
            "GROUP BY identity_hash HAVING COUNT(*) > 1)"
    )
    abstract fun countDuplicateConfirmedIdentities(): Long

    @Query(
        "SELECT COUNT(*) FROM debt_account WHERE status = :status AND NOT EXISTS (" +
            "SELECT 1 FROM debt_account_evidence WHERE account_id = debt_account.id " +
            "AND is_current = 1 AND strength = 'AUTHORITATIVE')"
    )
    abstract fun countStatusWithoutAuthoritativeEvidence(status: DebtAccountStatus): Long

    @Query("SELECT * FROM debt_account_evidence WHERE observation_id = :observationId AND content_hash = :contentHash AND is_current = 1 LIMIT 1")
    abstract fun findCurrentEvidenceForRevision(observationId: Long, contentHash: String): DebtAccountEvidenceEntity?

    @Query(
        "SELECT COUNT(*) FROM debt_account_evidence " +
            "INNER JOIN debt_account ON debt_account.id = debt_account_evidence.account_id " +
            "WHERE debt_account_evidence.event_kind = 'REPAYMENT' " +
            "AND debt_account_evidence.is_current = 1 " +
            "AND debt_account.status NOT IN ('BASELINED', 'RECONCILABLE')"
    )
    abstract fun countRepaymentsAwaitingBaseline(): Long
}

data class StatementCommitResult(
    val importId: Long,
    val duplicateFile: Boolean,
    val insertedRows: Int,
    val duplicateRows: Int,
)

data class StatementPrepareResult(
    val importId: Long,
    val duplicateCompleted: Boolean,
)

data class StatementRowBatchResult(
    val insertedRows: Int,
    val existingRows: Int,
)

@Dao
abstract class StatementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertImportIgnore(entity: StatementImportEntity): Long

    @Insert
    protected abstract fun insertRow(entity: StatementRowEntity): Long

    @Insert
    protected abstract fun insertImportRow(entity: StatementImportRowEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertArtifactChunkIgnore(entity: StatementArtifactChunkEntity): Long

    @Query(
        "SELECT * FROM statement_import WHERE file_hash = :fileHash " +
            "AND parser_version = :parserVersion LIMIT 1"
    )
    abstract fun findImportByFileHashAndParser(
        fileHash: String,
        parserVersion: Int,
    ): StatementImportEntity?

    @Query("SELECT * FROM statement_import WHERE id = :importId LIMIT 1")
    protected abstract fun findImportById(importId: Long): StatementImportEntity?

    @Query(
        "SELECT chunk_hash FROM statement_artifact_chunk WHERE import_id = :importId " +
            "AND chunk_index = :chunkIndex LIMIT 1"
    )
    protected abstract fun findChunkHash(importId: Long, chunkIndex: Int): String?

    @Query(
        "SELECT statement_row.row_fingerprint FROM statement_import_row INNER JOIN statement_row " +
            "ON statement_row.id = statement_import_row.row_id " +
            "WHERE statement_import_row.import_id = :importId " +
            "AND statement_import_row.source_row_number = :sourceRowNumber LIMIT 1"
    )
    protected abstract fun findLinkedRowFingerprint(importId: Long, sourceRowNumber: Int): String?

    @Query("SELECT COUNT(*) FROM statement_artifact_chunk WHERE import_id = :importId")
    protected abstract fun countArtifactChunks(importId: Long): Long

    @Query("SELECT COUNT(*) FROM statement_import_row WHERE import_id = :importId")
    protected abstract fun countLinkedRows(importId: Long): Long

    @Query(
        "UPDATE statement_import SET status = 'IMPORTED_UNVERIFIED' " +
            "WHERE id = :importId AND status IN ('IMPORTING', 'IMPORT_FAILED')"
    )
    protected abstract fun markCompleted(importId: Long): Int

    @Query(
        "UPDATE statement_import SET status = 'IMPORT_FAILED' " +
            "WHERE id = :importId AND status = 'IMPORTING'"
    )
    abstract fun markFailed(importId: Long): Int

    @Query("UPDATE statement_import SET status = 'IMPORTING' WHERE id = :importId AND status = 'IMPORT_FAILED'")
    protected abstract fun markResuming(importId: Long): Int

    @Query(
        "SELECT statement_import.id AS id, statement_import.display_name AS display_name, " +
            "statement_import.source_kind AS source_kind, statement_import.format AS format, " +
            "statement_import.authority AS authority, statement_import.status AS status, " +
            "statement_import.observed_row_from_ms AS observed_row_from_ms, " +
            "statement_import.observed_row_to_ms AS observed_row_to_ms, " +
            "statement_import.valid_row_count AS valid_row_count, " +
            "statement_import.invalid_row_count AS invalid_row_count, " +
            "statement_import.ignored_footer_row_count AS ignored_footer_row_count, " +
            "statement_import.duplicate_row_count AS duplicate_row_count, " +
            "COUNT(statement_import_row.row_id) AS linked_row_count, " +
            "statement_import.imported_at_ms AS imported_at_ms " +
            "FROM statement_import LEFT JOIN statement_import_row " +
            "ON statement_import_row.import_id = statement_import.id " +
            "GROUP BY statement_import.id ORDER BY statement_import.imported_at_ms DESC"
    )
    abstract fun listImports(): List<StatementImportSummary>

    @Query("SELECT COUNT(*) FROM statement_import")
    abstract fun countImports(): Long

    @Query(
        "SELECT COUNT(*) FROM statement_import_row INNER JOIN statement_import " +
            "ON statement_import.id = statement_import_row.import_id " +
            "WHERE statement_import.status IN ('IMPORTED_UNVERIFIED', 'PERIOD_VALIDATED', 'RECONCILED')"
    )
    abstract fun countRows(): Long

    @Query(
        "SELECT COUNT(*) FROM statement_import WHERE authority = 'FORMAT_RECOGNIZED_UNVERIFIED' " +
            "AND status = 'IMPORTED_UNVERIFIED'"
    )
    abstract fun countAwaitingValidation(): Long

    @Query("SELECT COUNT(*) FROM statement_import WHERE status IN ('IMPORTING', 'IMPORT_FAILED')")
    abstract fun countIncompleteImports(): Long

    @Query(
        "SELECT COUNT(*) FROM statement_import WHERE " +
            "status IN ('IMPORTED_UNVERIFIED', 'PERIOD_VALIDATED', 'RECONCILED') AND (" +
            "(SELECT COUNT(*) FROM statement_artifact_chunk WHERE import_id = statement_import.id) " +
            "!= statement_import.artifact_chunk_count OR " +
            "(SELECT COUNT(*) FROM statement_import_row WHERE import_id = statement_import.id) " +
            "!= statement_import.valid_row_count)"
    )
    abstract fun countCompletedIntegrityFailures(): Long

    @Query(
        "SELECT COUNT(*) FROM statement_import_row LEFT JOIN statement_import " +
            "ON statement_import.id = statement_import_row.import_id LEFT JOIN statement_row " +
            "ON statement_row.id = statement_import_row.row_id " +
            "WHERE statement_import.id IS NULL OR statement_row.id IS NULL"
    )
    abstract fun countOrphanLinks(): Long

    @Transaction
    open fun prepareImport(importEntity: StatementImportEntity): StatementPrepareResult {
        val importId = insertImportIgnore(importEntity)
        if (importId == -1L) {
            val existing = findImportByFileHashAndParser(
                importEntity.fileHash,
                importEntity.parserVersion,
            )
                ?: error("账单文件摘要冲突后无法读取")
            check(existing.artifactSizeBytes == importEntity.artifactSizeBytes)
            check(existing.artifactChunkCount == importEntity.artifactChunkCount)
            check(existing.sourceKind == importEntity.sourceKind)
            check(existing.format == importEntity.format)
            check(existing.observedRowFromMs == importEntity.observedRowFromMs)
            check(existing.observedRowToMs == importEntity.observedRowToMs)
            check(existing.rawRowCount == importEntity.rawRowCount)
            check(existing.validRowCount == importEntity.validRowCount)
            check(existing.invalidRowCount == importEntity.invalidRowCount)
            check(existing.ignoredFooterRowCount == importEntity.ignoredFooterRowCount)
            val completed = existing.status in setOf(
                StatementImportStatus.IMPORTED_UNVERIFIED,
                StatementImportStatus.PERIOD_VALIDATED,
                StatementImportStatus.RECONCILED,
            )
            if (!completed) markResuming(existing.id)
            return StatementPrepareResult(existing.id, completed)
        }
        return StatementPrepareResult(importId, false)
    }

    @Transaction
    open fun appendArtifactChunk(entity: StatementArtifactChunkEntity) {
        if (insertArtifactChunkIgnore(entity) == -1L) {
            check(findChunkHash(entity.importId, entity.chunkIndex) == entity.chunkHash) {
                "账单原文件分块摘要冲突"
            }
        }
    }

    @Transaction
    open fun appendRowBatch(
        importId: Long,
        rows: List<Pair<Int, StatementRowEntity>>,
    ): StatementRowBatchResult {
        var insertedRows = 0
        var existingRows = 0
        rows.forEach { (sourceRowNumber, proposed) ->
            val existingFingerprint = findLinkedRowFingerprint(importId, sourceRowNumber)
            if (existingFingerprint != null) {
                check(existingFingerprint == proposed.rowFingerprint) { "账单恢复行内容冲突" }
                existingRows++
                return@forEach
            }
            val rowId = insertRow(proposed)
            insertedRows++
            insertImportRow(
                StatementImportRowEntity(
                    importId = importId,
                    rowId = rowId,
                    sourceRowNumber = sourceRowNumber,
                )
            )
        }
        return StatementRowBatchResult(insertedRows, existingRows)
    }

    @Transaction
    open fun finalizeImport(importId: Long, expectedChunks: Int, expectedRows: Int) {
        val imported = findImportById(importId) ?: error("账单批次不存在")
        check(imported.artifactChunkCount == expectedChunks) { "账单原文件分块声明不一致" }
        check(imported.validRowCount == expectedRows) { "账单证据行声明不一致" }
        check(countArtifactChunks(importId) == expectedChunks.toLong()) { "账单原文件分块不完整" }
        check(countLinkedRows(importId) == expectedRows.toLong()) { "账单证据行不完整" }
        check(markCompleted(importId) == 1) { "账单批次无法完成" }
    }
}

@Dao
abstract class CoverageGapDao {
    @Insert
    abstract fun insert(gap: CoverageGapEntity): Long

    @Query("SELECT COUNT(*) FROM coverage_gap WHERE detector = :detector AND state = 'ACTIVE'")
    abstract fun countOpenByDetector(detector: String): Long

    @Query("UPDATE coverage_gap SET ended_at_ms = :endedAtMs, state = 'CLOSED' WHERE detector = :detector AND state = 'ACTIVE'")
    abstract fun closeOpenByDetector(detector: String, endedAtMs: Long)

    @Query("SELECT COUNT(*) FROM coverage_gap WHERE state = 'ACTIVE'")
    abstract fun countOpen(): Long

    @Query("SELECT * FROM coverage_gap WHERE state = 'ACTIVE' ORDER BY started_at_ms DESC LIMIT 50")
    abstract fun openGaps(): List<CoverageGapEntity>

    /** 兼容早期试验库：旧 OPEN 值按是否已有结束时间归一化，保留全部历史行。 */
    @Query("UPDATE coverage_gap SET state = CASE WHEN ended_at_ms IS NULL THEN 'ACTIVE' ELSE 'CLOSED' END WHERE state = 'OPEN'")
    abstract fun normalizeLegacyOpenState(): Int
}

@Dao
interface NotificationRemovalDao {
    @Insert
    fun insert(removal: NotificationRemovalEntity): Long
}
