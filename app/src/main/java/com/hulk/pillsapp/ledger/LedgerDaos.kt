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

@Dao
abstract class ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertIgnore(observation: RawObservationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract fun insertRevisionIgnore(revision: ObservationRevisionEntity): Long

    @Query("SELECT * FROM raw_observation WHERE source = :source AND source_key = :sourceKey LIMIT 1")
    abstract fun findBySourceAndKey(source: ObservationSource, sourceKey: String): RawObservationEntity?

    @Query("SELECT * FROM raw_observation WHERE id = :id")
    abstract fun findById(id: Long): RawObservationEntity?

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
        "DELETE FROM debt_account WHERE status IN ('SUSPECTED', 'IDENTIFIED') " +
            "AND NOT EXISTS (SELECT 1 FROM debt_account_evidence " +
            "WHERE account_id = debt_account.id AND is_current = 1)"
    )
    abstract fun deleteOrphanUnbaselinedAccounts(): Int

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

    @Query("SELECT COUNT(*) FROM debt_account WHERE status = :status")
    abstract fun countByStatus(status: DebtAccountStatus): Long

    @Query("SELECT * FROM debt_account WHERE status != 'EXCLUDED' ORDER BY last_seen_at_ms DESC, id DESC")
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
