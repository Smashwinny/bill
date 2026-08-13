package com.hulk.pillsapp.ledger

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * M1 完整性数据内核实体（V1 §3.2 + V1.1 §7）。
 *
 * 与 V1.1 §4 表格的一处实现级偏差（有意为之）：
 * raw_observation 的唯一约束是 (source, source_key) 而非 (source, source_key, content_hash)。
 * 若按三列建唯一键，"同 key 不同内容"会插入第二条观察行，破坏 V1 §5.4 的修订链语义。
 * 正确做法：同 key 同哈希 → duplicate_count + 1；同 key 不同哈希 → observation_revision 新行。
 * "不重复"仍由数据库唯一约束兜底，而非应用层判断。
 */

enum class ObservationSource { NOTIFICATION, SMS, A11Y, BILL_IMPORT }

enum class CapturePath {
    LIVE_CALLBACK,
    RECONNECT_SWEEP,
    SMS_BROADCAST,
    SMS_BACKFILL,
    A11Y,
    BILL_IMPORT,
    LEGACY_T03_MIGRATION,
}

enum class ParseState { PENDING_PARSE, PARSED, PARSE_FAILED, IGNORED_NON_FINANCIAL }

/** T00 §2 交易状态机；REFUNDED 是独立事件不是状态，见 refund 侧建模（M4 完善）。 */
enum class TxStatus { DETECTED, SUCCESS, PARTIALLY_REFUNDED, FULLY_REFUNDED, REVERSED, DISCARDED }

enum class TxType { PAYMENT, REFUND, TRANSFER, FEE, REVERSAL, INCOME, UNKNOWN }

enum class GapState { ACTIVE, CLOSED, BACKFILLED }

enum class StatementSourceKind { ALIPAY, WECHAT, BANK, UNKNOWN }

enum class StatementFormat {
    ALIPAY_CSV,
    ALIPAY_XLSX,
    WECHAT_CSV,
    WECHAT_XLSX,
    BANK_CSV,
    BANK_XLSX,
}

enum class StatementAuthority {
    FORMAT_RECOGNIZED_UNVERIFIED,
    PERIOD_VALIDATED,
    AUTHORITATIVE,
}

enum class StatementImportStatus {
    IMPORTING,
    IMPORT_FAILED,
    IMPORTED_UNVERIFIED,
    PERIOD_VALIDATED,
    RECONCILED,
}

enum class StatementDirection { OUT, IN, NEUTRAL, UNKNOWN }

enum class BehaviorKind { PAYMENT, REFUND }

enum class BehaviorCandidateState { PENDING, CONFIRMED, REJECTED, AUTO_RECORDED, UNDONE }

enum class BehaviorDecision { CONFIRM_PAYMENT, CONFIRM_REFUND, REJECT, AUTO_RECORD, UNDO_AUTO }

enum class BehaviorDecisionActor { USER, MODEL }

object GapDetectors {
    const val LISTENER_CALLBACK = "LISTENER_CALLBACK"
    const val BOOT_CHECK = "BOOT_CHECK"
    const val HEALTH_CHECK = "HEALTH_CHECK"
    const val A11Y_SERVICE = "A11Y_SERVICE"
    const val A11Y_AMBIGUOUS_REPEAT = "A11Y_AMBIGUOUS_REPEAT"
}

@Entity(
    tableName = "raw_observation",
    indices = [
        Index(value = ["source", "source_key"], unique = true),
        Index(value = ["parse_state"]),
        Index(value = ["post_time_ms"]),
    ],
)
data class RawObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: ObservationSource,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "user_handle") val userHandle: Int,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "post_time_ms") val postTimeMs: Long,
    @ColumnInfo(name = "received_at_ms") val receivedAtMs: Long,
    val title: String,
    val body: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "capture_path") val capturePath: CapturePath,
    @ColumnInfo(name = "parse_state") val parseState: ParseState,
    @ColumnInfo(name = "duplicate_count") val duplicateCount: Long = 0,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "observation_revision",
    foreignKeys = [
        ForeignKey(
            entity = RawObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["observation_id", "revision_hash"], unique = true)],
)
data class ObservationRevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "revision_hash") val revisionHash: String,
    val title: String,
    val body: String,
    @ColumnInfo(name = "revised_at_ms") val revisedAtMs: Long,
)

@Entity(
    tableName = "canonical_transaction",
    indices = [Index(value = ["strong_id_hash"], unique = true)],
)
data class CanonicalTransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "strong_id_hash") val strongIdHash: String?,
    val type: TxType,
    val status: TxStatus,
    @ColumnInfo(name = "amount_cents") val amountCents: Long?,
    val currency: String = "CNY",
    @ColumnInfo(name = "merchant_hint") val merchantHint: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    /** 对账补账来源通道；实时发现的候选为 null（V1.1 §7）。 */
    @ColumnInfo(name = "backfilled_from") val backfilledFrom: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "ledger_entry",
    indices = [Index(value = ["canonical_tx_id", "account_id", "direction"], unique = true)],
)
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "canonical_tx_id") val canonicalTxId: Long,
    /** 资金账户标识；账户实体在 M3 建立，M1 先以字符串占位。 */
    @ColumnInfo(name = "account_id") val accountId: String,
    /** DEBIT=余额减少，CREDIT=余额增加。 */
    val direction: String,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Entity(
    tableName = "evidence_link",
    indices = [
        Index(value = ["observation_id"], unique = true),
        Index(value = ["canonical_tx_id"]),
    ],
)
data class EvidenceLinkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "canonical_tx_id") val canonicalTxId: Long,
    /** STRONG_ID / STRONG_ID_MERGED / WEAK_OBSERVATION_ONLY / SET_MATCH（M4）。 */
    @ColumnInfo(name = "match_reason") val matchReason: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/**
 * 每个无障碍终态回调的不可变应用收据。outbox 在数据库提交后、文件删除前崩溃时，
 * occurrence_id 的主键保证整次重放（包括歧义计数和缺口副作用）只应用一次。
 */
@Entity(
    tableName = "behavior_signal_receipt",
    foreignKeys = [
        ForeignKey(
            entity = RawObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["observation_id"])],
)
data class BehaviorSignalReceiptEntity(
    @PrimaryKey
    @ColumnInfo(name = "occurrence_id") val occurrenceId: String,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "ambiguous_repeat") val ambiguousRepeat: Boolean,
    @ColumnInfo(name = "applied_at_ms") val appliedAtMs: Long,
)

/**
 * M5 行为片段。原始页面文本和截图永不落库；这里只保存脱敏事件摘要与用户决定。
 * observation/canonical 一一对应，确保一次行为确认只改变已有候选，不额外复制交易。
 */
@Entity(
    tableName = "behavior_candidate",
    foreignKeys = [
        ForeignKey(
            entity = RawObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = CanonicalTransactionEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonical_tx_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["public_id"], unique = true),
        Index(value = ["observation_id"], unique = true),
        Index(value = ["canonical_tx_id"], unique = true),
        Index(value = ["template_key"]),
        Index(value = ["state"]),
    ],
)
data class BehaviorCandidateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "public_id") val publicId: String,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "canonical_tx_id") val canonicalTxId: Long,
    @ColumnInfo(name = "template_key") val templateKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    val kind: BehaviorKind,
    @ColumnInfo(name = "amount_cents") val amountCents: Long?,
    val currency: String = "CNY",
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    val confidence: Int,
    @ColumnInfo(name = "consumed_intent") val consumedIntent: Boolean,
    @ColumnInfo(name = "route_signature") val routeSignature: String,
    @ColumnInfo(name = "app_version_code") val appVersionCode: Long,
    @ColumnInfo(name = "ambiguous_repeat_count") val ambiguousRepeatCount: Long,
    @ColumnInfo(name = "feature_summary") val featureSummary: String,
    val purpose: String?,
    val state: BehaviorCandidateState,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
    @ColumnInfo(name = "decided_at_ms") val decidedAtMs: Long?,
)

/** 只按脱敏事件序列摘要学习；模型自己的决定永不反向训练模板。 */
@Entity(tableName = "behavior_template")
data class BehaviorTemplateEntity(
    @PrimaryKey
    @ColumnInfo(name = "template_key") val templateKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    val kind: BehaviorKind,
    @ColumnInfo(name = "route_signature") val routeSignature: String,
    @ColumnInfo(name = "app_version_code") val appVersionCode: Long,
    @ColumnInfo(name = "positive_count") val positiveCount: Int,
    @ColumnInfo(name = "negative_count") val negativeCount: Int,
    @ColumnInfo(name = "consecutive_positive_count") val consecutivePositiveCount: Int,
    @ColumnInfo(name = "auto_enabled") val autoEnabled: Boolean,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

/** 用户/模型决定不可变审计行；候选状态可以更新，但历史决定不覆盖。 */
@Entity(
    tableName = "behavior_decision",
    foreignKeys = [
        ForeignKey(
            entity = BehaviorCandidateEntity::class,
            parentColumns = ["id"],
            childColumns = ["candidate_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["candidate_id"])],
)
data class BehaviorDecisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "candidate_id") val candidateId: Long,
    val decision: BehaviorDecision,
    val actor: BehaviorDecisionActor,
    val kind: BehaviorKind,
    @ColumnInfo(name = "amount_cents") val amountCents: Long?,
    val purpose: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/**
 * M3 自动发现的负债账户候选，不是交易。M3 不保存“当前余额”；M4 完成账单来源、账户身份、
 * 覆盖期和完整性校验后，另建不可变余额快照，才能升级 BASELINED。
 */
@Entity(
    tableName = "debt_account",
    indices = [
        Index(value = ["public_id"], unique = true),
        Index(value = ["cluster_hash"], unique = true),
        Index(value = ["identity_hash"], unique = true),
        Index(value = ["status"]),
    ],
)
data class DebtAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 随机稳定 ID，供未来跨设备同步引用；不含任何账户信息。 */
    @ColumnInfo(name = "public_id") val publicId: String,
    /** 仅用于折叠疑似线索，不能驱动正式账目归并。 */
    @ColumnInfo(name = "cluster_hash") val clusterHash: String,
    /** 只有稳定账户/合同标识通过来源验证后才存在；尾号永远不进入此字段。 */
    @ColumnInfo(name = "identity_hash") val identityHash: String?,
    val product: DebtProduct,
    @ColumnInfo(name = "institution_code") val institutionCode: String,
    @ColumnInfo(name = "institution_label") val institutionLabel: String,
    @ColumnInfo(name = "display_label") val displayLabel: String,
    @ColumnInfo(name = "masked_suffix") val maskedSuffix: String?,
    @ColumnInfo(name = "user_handle") val userHandle: Int,
    val currency: String = "CNY",
    val status: DebtAccountStatus,
    val confidence: Int,
    @ColumnInfo(name = "last_event_kind") val lastEventKind: DebtEventKind,
    @ColumnInfo(name = "last_evidence_strength") val lastEvidenceStrength: DebtEvidenceStrength,
    @ColumnInfo(name = "due_day_of_month") val dueDayOfMonth: Int?,
    @ColumnInfo(name = "first_seen_at_ms") val firstSeenAtMs: Long,
    @ColumnInfo(name = "last_seen_at_ms") val lastSeenAtMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

/** 一条观察修订对一个负债候选的脱敏结构化证据；新修订只标旧证据失效，不删除审计链。 */
@Entity(
    tableName = "debt_account_evidence",
    foreignKeys = [
        ForeignKey(
            entity = RawObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = DebtAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["account_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(
            value = ["observation_id", "content_hash", "parser_version", "signal_fingerprint"],
            unique = true,
        ),
        Index(value = ["account_id"]),
        Index(value = ["event_kind"]),
        Index(value = ["observation_id", "is_current"]),
    ],
)
data class DebtAccountEvidenceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    @ColumnInfo(name = "signal_fingerprint") val signalFingerprint: String,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    @ColumnInfo(name = "event_kind") val eventKind: DebtEventKind,
    val strength: DebtEvidenceStrength,
    @ColumnInfo(name = "amount_role") val amountRole: DebtAmountRole,
    @ColumnInfo(name = "amount_cents") val amountCents: Long?,
    @ColumnInfo(name = "due_day_of_month") val dueDayOfMonth: Int?,
    @ColumnInfo(name = "observed_at_ms") val observedAtMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** 负面结果也保留审计历史；每个观察只有一条 current 游标用于避免启动时全表重扫。 */
@Entity(
    tableName = "account_discovery_scan",
    primaryKeys = ["observation_id", "content_hash", "parser_version"],
    foreignKeys = [
        ForeignKey(
            entity = RawObservationEntity::class,
            parentColumns = ["id"],
            childColumns = ["observation_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["parser_version"]), Index(value = ["observation_id", "is_current"])],
)
data class AccountDiscoveryScanEntity(
    @ColumnInfo(name = "observation_id") val observationId: Long,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    @ColumnInfo(name = "is_current") val isCurrent: Boolean,
    val result: DiscoveryScanResult,
    @ColumnInfo(name = "scanned_at_ms") val scannedAtMs: Long,
)

@Entity(tableName = "reconciliation_run")
data class ReconciliationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "account_id") val accountId: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    @ColumnInfo(name = "finished_at_ms") val finishedAtMs: Long?,
    @ColumnInfo(name = "coverage_from_ms") val coverageFromMs: Long?,
    @ColumnInfo(name = "coverage_to_ms") val coverageToMs: Long?,
    /** UNPROVEN / INCOMPLETE / CONFLICTED / RECONCILED（V1 §7）。 */
    val result: String?,
    val note: String?,
)

@Entity(
    tableName = "coverage_gap",
    indices = [Index(value = ["detector", "ended_at_ms"])],
)
data class CoverageGapEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val detector: String,
    @ColumnInfo(name = "started_at_ms") val startedAtMs: Long,
    /** null 表示缺口仍在持续；恢复连接后转 CLOSED，完成权威对账后转 BACKFILLED。 */
    @ColumnInfo(name = "ended_at_ms") val endedAtMs: Long?,
    val state: GapState,
    val note: String?,
)

/** 通知移除事件只记元数据，不删已存观察（V1.1 §3.1）。 */
@Entity(tableName = "notification_removal")
data class NotificationRemovalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_key") val sourceKey: String,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "user_handle") val userHandle: Int,
    val reason: Int,
    @ColumnInfo(name = "removed_at_ms") val removedAtMs: Long,
)

/** M4.0 文件级审计；文件选择器不能证明官方来源，首次导入只能是待校验状态。 */
@Entity(
    tableName = "statement_import",
    indices = [
        Index(value = ["public_id"], unique = true),
        // 同一解析器重复选择同一文件幂等；解析器升级后必须允许形成新的审计批次。
        Index(value = ["file_hash", "parser_version"], unique = true),
    ],
)
data class StatementImportEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "public_id") val publicId: String,
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "source_kind") val sourceKind: StatementSourceKind,
    val format: StatementFormat,
    @ColumnInfo(name = "parser_version") val parserVersion: Int,
    val authority: StatementAuthority,
    val status: StatementImportStatus,
    /** 仅为文件内最早/最晚交易行时间，不代表官方账期边界或完整覆盖。 */
    @ColumnInfo(name = "observed_row_from_ms") val observedRowFromMs: Long,
    @ColumnInfo(name = "observed_row_to_ms") val observedRowToMs: Long,
    @ColumnInfo(name = "raw_row_count") val rawRowCount: Int,
    @ColumnInfo(name = "valid_row_count") val validRowCount: Int,
    @ColumnInfo(name = "invalid_row_count") val invalidRowCount: Int,
    @ColumnInfo(name = "ignored_footer_row_count") val ignoredFooterRowCount: Int,
    @ColumnInfo(name = "duplicate_row_count") val duplicateRowCount: Int,
    @ColumnInfo(name = "artifact_size_bytes") val artifactSizeBytes: Int,
    @ColumnInfo(name = "artifact_chunk_count") val artifactChunkCount: Int,
    @ColumnInfo(name = "imported_at_ms") val importedAtMs: Long,
)

/** 原始选择文件的加密分块；保留元数据、表头和页脚，供后续账户/账期复核。 */
@Entity(
    tableName = "statement_artifact_chunk",
    primaryKeys = ["import_id", "chunk_index"],
    foreignKeys = [
        ForeignKey(
            entity = StatementImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
)
data class StatementArtifactChunkEntity(
    @ColumnInfo(name = "import_id") val importId: Long,
    @ColumnInfo(name = "chunk_index") val chunkIndex: Int,
    @ColumnInfo(name = "chunk_hash") val chunkHash: String,
    val bytes: ByteArray,
)

/** 账单中的不可变原始/归一化行；原文只位于 SQLCipher 加密数据库。 */
@Entity(
    tableName = "statement_row",
    indices = [
        // 无账户作用域时，相同内容可能是两笔真实交易；指纹只用于审计，不能跨文件去重。
        Index(value = ["row_fingerprint"]),
        Index(value = ["external_id_hash"]),
        Index(value = ["occurred_at_ms"]),
    ],
)
data class StatementRowEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "source_kind") val sourceKind: StatementSourceKind,
    @ColumnInfo(name = "row_fingerprint") val rowFingerprint: String,
    @ColumnInfo(name = "external_id_hash") val externalIdHash: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val currency: String,
    val direction: StatementDirection,
    @ColumnInfo(name = "tx_type") val txType: TxType,
    @ColumnInfo(name = "tx_status") val txStatus: String,
    @ColumnInfo(name = "counterparty") val counterparty: String?,
    @ColumnInfo(name = "item_description") val itemDescription: String?,
    @ColumnInfo(name = "raw_record") val rawRecord: String,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

/** 保留重叠账期中“哪份文件包含哪些行”的审计关系。 */
@Entity(
    tableName = "statement_import_row",
    primaryKeys = ["import_id", "source_row_number"],
    foreignKeys = [
        ForeignKey(
            entity = StatementImportEntity::class,
            parentColumns = ["id"],
            childColumns = ["import_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = StatementRowEntity::class,
            parentColumns = ["id"],
            childColumns = ["row_id"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["row_id"], unique = true)],
)
data class StatementImportRowEntity(
    @ColumnInfo(name = "import_id") val importId: Long,
    @ColumnInfo(name = "row_id") val rowId: Long,
    @ColumnInfo(name = "source_row_number") val sourceRowNumber: Int,
)
