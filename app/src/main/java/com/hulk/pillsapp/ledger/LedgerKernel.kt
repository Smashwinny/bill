package com.hulk.pillsapp.ledger

import android.app.Notification
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.service.notification.StatusBarNotification
import androidx.room.Room
import com.hulk.pillsapp.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class KernelStatus(
    val observationCount: Long = 0,
    val candidateCount: Long = 0,
    val pendingParseCount: Long = 0,
    val openGapCount: Long = 0,
    val openGaps: List<CoverageGapEntity> = emptyList(),
    val debtAccountCount: Long = 0,
    val suspectedDebtCount: Long = 0,
    val identifiedDebtCount: Long = 0,
    val baselinedDebtCount: Long = 0,
    val reconcilableDebtCount: Long = 0,
    val conflictedDebtCount: Long = 0,
    val debtDiscoveryPendingCount: Long = 0,
    val repaymentsAwaitingBaselineCount: Long = 0,
    val eligibleRevisionCount: Long = 0,
    val discoveryScannedCount: Long = 0,
    val discoveryFailedCount: Long = 0,
    val debtEvidenceCount: Long = 0,
    val orphanDebtEvidenceCount: Long = 0,
    val duplicateDebtSignalCount: Long = 0,
    val duplicateConfirmedIdentityCount: Long = 0,
    val nonAuthoritativeBaselineCount: Long = 0,
    val nonAuthoritativeReconcilableCount: Long = 0,
    val kernelAsyncFailureCount: Long = 0,
    val coverageSourceCount: Long = 0,
    val sourcesWithoutStatementCount: Long = 0,
    val statementImportCount: Long = 0,
    val statementRowCount: Long = 0,
    val statementAwaitingValidationCount: Long = 0,
    val statementIncompleteImportCount: Long = 0,
    val statementIntegrityFailureCount: Long = 0,
    val orphanStatementLinkCount: Long = 0,
    val behaviorPendingCount: Long = 0,
    val behaviorConfirmedCount: Long = 0,
    val behaviorAutoRecordedCount: Long = 0,
    val behaviorAutoTemplateCount: Long = 0,
    val behaviorDecisionCount: Long = 0,
    val behaviorSignalReceiptCount: Long = 0,
    val orphanBehaviorSignalReceiptCount: Long = 0,
    val orphanBehaviorCount: Long = 0,
    val a11yHeartbeatFresh: Boolean = false,
    val behaviorCandidates: List<BehaviorCandidateEntity> = emptyList(),
    val sourceCoverage: List<SourceCoverageItem> = emptyList(),
    val statementImports: List<StatementImportSummary> = emptyList(),
    val debtAccounts: List<DebtAccountEntity> = emptyList(),
    val lastSweepAtMs: Long? = null,
    val t03Migrated: Boolean = false,
)

/**
 * 耐久回调的不可交换顺序：数据库提交后必须先安排幂等解析，再删除 outbox。
 * 即使删除或目录 fsync 失败，当前进程也已有解析任务，重放仍可安全重复安排。
 */
internal object DurableObservationCommitOrder {
    fun commit(
        ingest: () -> IngestOutcome,
        scheduleProcessing: (IngestOutcome) -> Unit,
        completeOutbox: () -> Unit,
    ): IngestOutcome {
        val outcome = ingest()
        scheduleProcessing(outcome)
        completeOutbox()
        return outcome
    }
}

internal object ObservationRecoveryRules {
    fun isComplete(hasPendingOutbox: Boolean, pendingParseCount: Long): Boolean =
        !hasPendingOutbox && pendingParseCount == 0L
}

internal object CallbackPersistencePolicy {
    /** fsync outbox 已经是回调返回后的耐久承诺；只有 outbox 失败才同步等数据库兜底。 */
    fun shouldWaitForDatabase(stagedDurably: Boolean): Boolean = !stagedDurably
}

internal object RetryScheduleGuard {
    fun scheduleNoThrow(
        scheduled: java.util.concurrent.atomic.AtomicBoolean,
        schedule: (Runnable) -> Unit,
        retry: () -> Unit,
        onFailure: (Throwable) -> Unit,
    ): Boolean {
        if (!scheduled.compareAndSet(false, true)) return true
        return try {
            schedule(
                Runnable {
                    scheduled.set(false)
                    retry()
                }
            )
            true
        } catch (failure: Throwable) {
            scheduled.set(false)
            runCatching { onFailure(failure) }
            false
        }
    }
}

/**
 * V1.1 §4 写入管线的实现。
 *
 * 崩溃安全模型：所有系统回调（通知/缺口）都通过 [runSync] 在调用线程上**同步等待**
 * 单线程执行器完成数据库写入后才返回。进程在回调返回后被杀不会丢事件；
 * 回调返回前被杀则该事件从未被确认，属于可检测缺口而非静默丢失。
 *
 * 解析、匹配、状态刷新全部走 [submitAsync]，绝不在回调线程执行。
 */
object LedgerKernel {
    private const val DB_NAME = "ledger_kernel.db"
    private const val PREFS_NAME = "ledger_kernel_prefs"
    private const val PREF_T03_MIGRATED = "t03_migrated"
    private const val PREF_LAST_SWEEP_AT = "last_sweep_at_ms"
    private const val PREF_LAST_HEALTH_CHECK_AT = "last_health_check_at_ms"
    private const val PREF_LAST_A11Y_HEARTBEAT_AT = "last_a11y_heartbeat_at_ms"
    private const val STATEMENT_IMPORT_ROW_BATCH_SIZE = 25

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ledger-ingest")
    }
    private val behaviorExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "behavior-ingest")
    }
    private val callbackExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "system-callback-ingest")
    }
    private val observationRetryScheduled = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var database: LedgerDatabase? = null

    @Volatile
    private var behaviorOutbox: BehaviorSignalOutbox? = null

    @Volatile
    private var observationOutbox: ObservationOutbox? = null

    @Volatile
    private var executorThread: Thread? = null

    @Volatile
    private var callbackExecutorThread: Thread? = null

    private val callbackTimestampLock = Any()
    private var callbackTimestampFloor = 0L

    private val _status = MutableStateFlow(KernelStatus())
    val status: StateFlow<KernelStatus> = _status.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (database != null) return
        appContext = context.applicationContext
        behaviorOutbox = BehaviorSignalOutbox(
            java.io.File(context.applicationContext.filesDir, "behavior_signal_outbox")
        )
        observationOutbox = ObservationOutbox(
            java.io.File(context.applicationContext.filesDir, "observation_outbox")
        )
        net.sqlcipher.database.SQLiteDatabase.loadLibs(context.applicationContext)
        val passphrase = DbCrypto.getOrCreatePassphrase(context)
        migratePlaintextIfNeeded(context, passphrase)
        // SupportFactory 首次打开数据库后会清空传入的口令数组，
        // 因此每个 factory 必须持有独立拷贝。
        database = Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, DB_NAME)
            .openHelperFactory(net.sqlcipher.database.SupportFactory(passphrase.copyOf()))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
        executor.submit { executorThread = Thread.currentThread() }.get(2, TimeUnit.SECONDS)
        callbackExecutor.submit { callbackExecutorThread = Thread.currentThread() }.get(2, TimeUnit.SECONDS)
        val databaseTimestampFloor = callbackExecutor.submit<Long> {
            requireDb().observationDao().maxReceivedAtMs()
        }.get(2, TimeUnit.SECONDS)
        val pendingTimestampFloor = runCatching {
            requireNotNull(observationOutbox).pending().maxOfOrNull { it.observation.receivedAtMs } ?: 0L
        }.onFailure(::recordAsyncFailure).getOrDefault(0L)
        callbackTimestampFloor = maxOf(databaseTimestampFloor, pendingTimestampFloor)
        runSync { requireDb().coverageGapDao().normalizeLegacyOpenState() }
    }

    private val sqlitePlaintextHeader = "SQLite format 3\u0000".toByteArray(Charsets.UTF_8)

    /**
     * M1 明文调试库 → SQLCipher 加密库的一次性迁移（V1 §8、V1.1 §8 M2）。
     *
     * 实现选择（有意不用 ATTACH/sqlcipher_export）：ATTACH 需把口令内联进 SQL，
     * 失败时口令会随异常消息进入 Logcat（T00 §5 红线）。改为逐表逐行复制，
     * 口令不出现在任何 SQL 里。SQLCipher 文件头为随机字节；以明文头判断是否已加密。
     * 迁移失败保留原文件，下次启动重试。
     */
    private val migrationTablesParentFirst = listOf(
        "raw_observation",
        "canonical_transaction",
        "behavior_template",
        "debt_account",
        "statement_import",
        "statement_artifact_chunk",
        "statement_row",
        "observation_revision",
        "evidence_link",
        "behavior_signal_receipt",
        "behavior_candidate",
        "behavior_decision",
        "debt_account_evidence",
        "account_discovery_scan",
        "statement_import_row",
        "ledger_entry",
        "reconciliation_run",
        "coverage_gap",
        "notification_removal",
    )

    private fun migratePlaintextIfNeeded(context: Context, passphrase: ByteArray) {
        val dbFile = context.getDatabasePath(DB_NAME)
        if (!dbFile.exists()) return
        val header = ByteArray(sqlitePlaintextHeader.size)
        val read = dbFile.inputStream().use { it.read(header) }
        if (read < header.size || !header.contentEquals(sqlitePlaintextHeader)) return

        val encName = "$DB_NAME.enc"
        val encFile = context.getDatabasePath(encName)
        // 清掉可能存在的失败残留
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            java.io.File(encFile.absolutePath + suffix).delete()
        }

        // 框架 SQLite 读明文旧库，Room+SQLCipher 开加密新库，逐表逐行复制；
        // 口令不进入任何 SQL 语句，避免异常消息带口令进 Logcat（T00 §5）。
        val plain = android.database.sqlite.SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        val encryptedDb = Room.databaseBuilder(context.applicationContext, LedgerDatabase::class.java, encName)
            .openHelperFactory(net.sqlcipher.database.SupportFactory(passphrase.copyOf()))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()
        try {
            val writable = encryptedDb.openHelper.writableDatabase
            migrationTablesParentFirst.forEach { table ->
                if (!plaintextTableExists(plain, table)) return@forEach
                plain.query(table, null, null, null, null, null, null).use { cursor ->
                    val values = android.content.ContentValues()
                    while (cursor.moveToNext()) {
                        values.clear()
                        android.database.DatabaseUtils.cursorRowToContentValues(cursor, values)
                        writable.insert(
                            table,
                            android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE,
                            values,
                        )
                    }
                }
            }
        } finally {
            plain.close()
            encryptedDb.close()
        }

        val suffixes = listOf("", "-wal", "-shm", "-journal")
        val backupBase = java.io.File(dbFile.parentFile, "$DB_NAME.plain-backup-${System.currentTimeMillis()}")
        val movedOld = ArrayList<Pair<java.io.File, java.io.File>>()
        try {
            // 先将原库改名为可恢复备份，再把已关闭的加密库放到正式路径。
            // 所有文件位于同一目录，rename 是同文件系统操作；任一步失败都回滚原库。
            suffixes.forEach { suffix ->
                val old = java.io.File(dbFile.absolutePath + suffix)
                if (old.exists()) {
                    val backup = java.io.File(backupBase.absolutePath + suffix)
                    if (!old.renameTo(backup)) error("原数据库备份失败: $suffix")
                    movedOld += old to backup
                }
            }
            suffixes.forEach { suffix ->
                val encrypted = java.io.File(encFile.absolutePath + suffix)
                if (encrypted.exists()) {
                    val target = java.io.File(dbFile.absolutePath + suffix)
                    if (!encrypted.renameTo(target)) error("加密数据库替换失败: $suffix")
                }
            }
            if (!dbFile.exists()) error("加密数据库替换后主文件不存在")
        } catch (failure: Throwable) {
            // 删除不完整的新正式文件并恢复原库；备份本身始终保留到恢复成功。
            suffixes.forEach { suffix -> java.io.File(dbFile.absolutePath + suffix).delete() }
            movedOld.forEach { (old, backup) ->
                if (backup.exists() && !backup.renameTo(old)) {
                    throw IllegalStateException("加密迁移失败且原数据库恢复失败", failure)
                }
            }
            throw failure
        }
    }

    private fun plaintextTableExists(db: android.database.sqlite.SQLiteDatabase, table: String): Boolean =
        db.rawQuery(
            "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ? LIMIT 1",
            arrayOf(table),
        ).use { it.moveToFirst() }

    private fun requireDb(): LedgerDatabase =
        database ?: error("LedgerKernel.init() 未调用")

    /** 回调线程同步等待写入完成；若已在执行器线程上（如补偿扫描批处理）则直接执行，避免自死锁。 */
    private fun <T> runSync(block: () -> T): T =
        if (Thread.currentThread() === executorThread) {
            block()
        } else {
            executor.submit<T> { block() }.get(3, TimeUnit.SECONDS)
        }

    /** 通知/SMS 原始观察的高优先级短事务，不排在解析、迁移或状态刷新长队列之后。 */
    private fun <T> runCallbackSync(block: () -> T): T =
        if (Thread.currentThread() === callbackExecutorThread) {
            block()
        } else {
            callbackExecutor.submit<T> { block() }.get(3, TimeUnit.SECONDS)
        }

    private fun submitAsync(block: () -> Unit) {
        executor.submit {
            try {
                block()
            } catch (failure: Throwable) {
                // 不记录异常消息（可能含敏感内容），但留下类型与时间，避免完整性故障静默。
                recordAsyncFailure(failure)
            }
        }
    }

    private fun accountHash(material: String): String =
        AccountIdentityHasher.hash(requireNotNull(appContext), material)

    // ------------------------------------------------------------------
    // 通知采集（V1.1 §3.1）
    // ------------------------------------------------------------------

    /**
     * UserHandle.getIdentifier() 是系统隐藏 API，无法直接调用。
     * UserHandle.hashCode() 的 AOSP 实现即 user id，作为公开替代；
     * 异常时归入主空间 0。
     */
    private fun userHandleIdOf(sbn: StatusBarNotification): Int = try {
        sbn.user?.hashCode() ?: 0
    } catch (_: Throwable) {
        0
    }

    fun mapNotification(
        sbn: StatusBarNotification,
        capturePath: CapturePath,
        receivedAtMs: Long,
    ): RawObservationEntity {
        val key = sbn.key?.takeIf { it.isNotBlank() } ?: "${sbn.packageName}-${sbn.postTime}"
        val extras = sbn.notification?.extras
        val title = (extras?.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
        val body = (extras?.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
        val userHandle = userHandleIdOf(sbn)
        return RawObservationEntity(
            source = ObservationSource.NOTIFICATION,
            sourceKey = "$userHandle:$key",
            userHandle = userHandle,
            packageName = sbn.packageName.orEmpty(),
            postTimeMs = sbn.postTime,
            receivedAtMs = receivedAtMs,
            title = title,
            body = body,
            contentHash = sha256Hex(title + "\u0000" + body),
            capturePath = capturePath,
            parseState = ParseState.PENDING_PARSE,
            createdAtMs = receivedAtMs,
        )
    }

    /** 通知回调入口：同步落盘，解析异步。 */
    fun ingestNotification(sbn: StatusBarNotification, capturePath: CapturePath) {
        val entity = mapNotification(sbn, capturePath, System.currentTimeMillis())
        ingestObservation(entity)
    }

    fun ingestObservation(entity: RawObservationEntity) {
        val durableEntity = normalizeCallbackTimestamp(entity)
        val outbox = requireNotNull(observationOutbox)
        val staged = runCatching { outbox.stage(durableEntity) }
            .onFailure {
                recordAsyncFailure(it)
                openGapAsync(
                    GapDetectors.CALLBACK_PERSISTENCE,
                    "通知/SMS 加密待办不可用：本次回调改走数据库直写，覆盖需权威对账",
                )
            }
            .getOrNull()
        val task = java.util.concurrent.Callable {
            try {
                if (staged != null) {
                    processStagedObservation(staged, durableEntity)
                } else {
                    val outcome = requireDb().observationDao().ingest(durableEntity)
                    scheduleObservationProcessing(outcome)
                }
            } catch (failure: Throwable) {
                recordAsyncFailure(failure)
                if (staged != null) {
                    openGapAsync(
                        GapDetectors.CALLBACK_OUTBOX,
                        "通知/SMS 加密待办入库延迟，后台持续重试并等待权威对账",
                    )
                    schedulePendingObservationRecovery()
                    scheduleObservationOutboxRetry()
                } else {
                    openGapAsync(
                        GapDetectors.CALLBACK_PERSISTENCE,
                        "通知/SMS 待办与数据库直写均失败，需权威对账",
                    )
                }
            }
            Unit
        }
        val future = try {
            callbackExecutor.submit(task)
        } catch (failure: Throwable) {
            recordAsyncFailure(failure)
            if (staged != null) {
                runCatching {
                    openGapAsync(
                        GapDetectors.CALLBACK_OUTBOX,
                        "通知/SMS 加密待办已落盘但后台提交器不可用，等待下次启动重放",
                    )
                }.onFailure(::recordAsyncFailure)
                scheduleObservationOutboxRetry()
            } else {
                runCatching {
                    openGapAsync(
                        GapDetectors.CALLBACK_PERSISTENCE,
                        "通知/SMS 待办与数据库提交器均不可用，需权威对账",
                    )
                }.onFailure(::recordAsyncFailure)
            }
            return
        }
        if (!CallbackPersistencePolicy.shouldWaitForDatabase(stagedDurably = staged != null)) {
            // 文件已经加密、fsync、原子 rename 并同步目录；此处立即归还系统主回调，
            // 避免通知风暴连续占用主线程而让 Activity 永远停在启动白屏。
            return
        }
        try {
            future.get(3, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // 仅 outbox 落盘失败的兜底路径会等待；此时已打开 CALLBACK_PERSISTENCE gap，
            // 数据库任务继续运行，但必须依赖权威对账，不能宣称本次观察已经耐久保存。
        } catch (failure: Throwable) {
            // Callable 自身也捕获失败；这里仅防提交器级异常，绝不向系统回调逸出。
            recordAsyncFailure(failure)
        }
    }

    private fun normalizeCallbackTimestamp(entity: RawObservationEntity): RawObservationEntity =
        synchronized(callbackTimestampLock) {
            val normalized = maxOf(entity.receivedAtMs, callbackTimestampFloor + 1L)
            callbackTimestampFloor = normalized
            entity.copy(
                receivedAtMs = normalized,
                createdAtMs = if (entity.createdAtMs == entity.receivedAtMs) {
                    normalized
                } else {
                    entity.createdAtMs
                },
            )
        }

    private fun processStagedObservation(
        staged: java.io.File,
        entity: RawObservationEntity,
    ): IngestOutcome {
        return DurableObservationCommitOrder.commit(
            ingest = { requireDb().observationDao().ingestDurableCallback(entity) },
            scheduleProcessing = { scheduleDurableObservationProcessing(it.id) },
            completeOutbox = { requireNotNull(observationOutbox).complete(staged) },
        )
    }

    private fun scheduleObservationProcessing(outcome: IngestOutcome) {
        when (outcome) {
            is IngestOutcome.Duplicate -> Unit // 重复投递不触发重新解析
            is IngestOutcome.New,
            is IngestOutcome.Revised,
            -> submitAsync {
                CandidatePromoter.process(requireDb(), outcome.id)
                DebtAccountDiscoverer.processPendingForObservation(requireDb(), outcome.id, ::accountHash)
                refreshStatusBlocking()
            }
        }
    }

    /**
     * outbox 重放得到 Duplicate 也可能是“DB 已提交、解析任务尚未安排”的崩溃残留，
     * 因而必须按 observation id 幂等推进，不能把 Duplicate 直接丢弃。
     */
    private fun scheduleDurableObservationProcessing(observationId: Long) {
        if (observationId < 0L) return
        submitAsync {
            CandidatePromoter.process(requireDb(), observationId)
            DebtAccountDiscoverer.processPendingForObservation(requireDb(), observationId, ::accountHash)
            refreshStatusBlocking()
        }
    }

    fun drainObservationOutbox() {
        callbackExecutor.submit {
            try {
                val pendingOutbox = requireNotNull(observationOutbox).pending()
                val pendingParseCount = requireDb().observationDao().countPendingParse()
                if (!ObservationRecoveryRules.isComplete(
                        hasPendingOutbox = pendingOutbox.isNotEmpty(),
                        pendingParseCount = pendingParseCount,
                    )
                ) {
                    openGapAsync(
                        GapDetectors.CALLBACK_OUTBOX,
                        "通知/SMS 加密待办或数据库待解析观察仍未完成",
                    )
                }
                pendingOutbox.forEach { pending ->
                    processStagedObservation(pending.file, pending.observation)
                }
                schedulePendingObservationRecovery()
            } catch (failure: Throwable) {
                recordAsyncFailure(failure)
                openGapAsync(
                    GapDetectors.CALLBACK_OUTBOX,
                    "通知/SMS 持久待办恢复失败，等待下次启动并需权威对账",
                )
                schedulePendingObservationRecovery()
                scheduleObservationOutboxRetry()
            }
        }
    }

    /** outbox 与数据库 PENDING_PARSE 同时清空后才能关闭耐久回放缺口。 */
    private fun schedulePendingObservationRecovery() {
        submitAsync {
            val dao = requireDb().observationDao()
            while (true) {
                val batch = dao.pendingParse(100)
                if (batch.isEmpty()) break
                batch.forEach {
                    CandidatePromoter.process(requireDb(), it.id)
                    DebtAccountDiscoverer.processPendingForObservation(requireDb(), it.id, ::accountHash)
                }
            }
            val fullyRecovered = ObservationRecoveryRules.isComplete(
                hasPendingOutbox = requireNotNull(observationOutbox).hasPending(),
                pendingParseCount = dao.countPendingParse(),
            )
            if (fullyRecovered) {
                requireDb().coverageGapDao().closeOpenByDetector(
                    GapDetectors.CALLBACK_OUTBOX,
                    System.currentTimeMillis(),
                )
            }
            refreshStatusBlocking()
        }
    }

    private fun scheduleObservationOutboxRetry() {
        RetryScheduleGuard.scheduleNoThrow(
            scheduled = observationRetryScheduled,
            schedule = { runnable ->
                callbackExecutor.schedule(
                    runnable,
                    30L,
                    TimeUnit.SECONDS,
                )
            },
            retry = ::drainObservationOutbox,
            onFailure = ::recordAsyncFailure,
        )
    }

    /**
     * 重连补偿扫描（V1.1 §3.1.2）：断连期间仍存活在通知栏的事件全部比对补录。
     * 幂等性由 (source, source_key) 唯一约束保证；进程中途被杀时下次重连会再次全量扫描。
     */
    fun sweepActiveNotifications(active: List<StatusBarNotification>) {
        // 快照在系统回调线程当场映射并分配严格单调时间；后到的 LIVE_CALLBACK 必然更新。
        val snapshotAtMs = System.currentTimeMillis()
        val snapshot = active.map { sbn ->
            normalizeCallbackTimestamp(
                mapNotification(sbn, CapturePath.RECONNECT_SWEEP, snapshotAtMs)
            )
        }
        submitAsync {
            val dao = requireDb().observationDao()
            snapshot.forEach { entity ->
                when (val outcome = dao.ingestDurableCallback(entity)) {
                    is IngestOutcome.Duplicate -> Unit
                    is IngestOutcome.New,
                    is IngestOutcome.Revised,
                    -> {
                        CandidatePromoter.process(requireDb(), outcome.id)
                        DebtAccountDiscoverer.processPendingForObservation(requireDb(), outcome.id, ::accountHash)
                    }
                }
            }
            appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                ?.edit()?.putLong(PREF_LAST_SWEEP_AT, snapshotAtMs)?.apply()
            refreshStatusBlocking()
        }
    }

    fun recordRemoval(sbn: StatusBarNotification, reason: Int) {
        val userHandle = userHandleIdOf(sbn)
        submitAsync {
            requireDb().notificationRemovalDao().insert(
                NotificationRemovalEntity(
                    sourceKey = "$userHandle:${sbn.key.orEmpty()}",
                    packageName = sbn.packageName.orEmpty(),
                    userHandle = userHandle,
                    reason = reason,
                    removedAtMs = System.currentTimeMillis(),
                )
            )
        }
    }

    // ------------------------------------------------------------------
    // 覆盖缺口（V1.1 §6）
    // ------------------------------------------------------------------

    fun openGap(detector: String, note: String?) {
        runSync {
            val dao = requireDb().coverageGapDao()
            if (dao.countOpenByDetector(detector) == 0L) {
                dao.insert(
                    CoverageGapEntity(
                        detector = detector,
                        startedAtMs = System.currentTimeMillis(),
                        endedAtMs = null,
                        state = GapState.ACTIVE,
                        note = note,
                    )
                )
            }
        }
        submitAsync { refreshStatusBlocking() }
    }

    /** 系统生命周期回调专用：按序入队，避免主线程因共享写入队列繁忙而超时崩溃。 */
    fun openGapAsync(
        detector: String,
        note: String?,
        startedAtMs: Long = System.currentTimeMillis(),
    ) {
        submitAsync {
            val dao = requireDb().coverageGapDao()
            if (dao.countOpenByDetector(detector) == 0L) {
                dao.insert(
                    CoverageGapEntity(
                        detector = detector,
                        startedAtMs = startedAtMs,
                        endedAtMs = null,
                        state = GapState.ACTIVE,
                        note = note,
                    )
                )
            }
            refreshStatusBlocking()
        }
    }

    /** 恢复连接将活动缺口转为 CLOSED；历史保留，待权威对账补齐后再转 BACKFILLED。 */
    fun closeOpenGaps(detector: String) {
        runSync { requireDb().coverageGapDao().closeOpenByDetector(detector, System.currentTimeMillis()) }
        submitAsync { refreshStatusBlocking() }
    }

    fun closeOpenGapsAsync(
        detector: String,
        endedAtMs: Long = System.currentTimeMillis(),
    ) {
        submitAsync {
            requireDb().coverageGapDao().closeOpenByDetector(detector, endedAtMs)
            refreshStatusBlocking()
        }
    }

    /**
     * 敏感应用安全模式在真正关闭无障碍后调用。心跳必须先同步失效，避免健康检查用
     * 暂停前的旧心跳提前关闭刚建立的覆盖缺口。
     */
    fun markA11yPaused(note: String) {
        val context = requireNotNull(appContext) { "LedgerKernel 尚未初始化" }
        check(
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_A11Y_HEARTBEAT_AT, 0L)
                .commit()
        ) { "无障碍暂停心跳无法持久化" }
        runCallbackSync {
            val dao = requireDb().coverageGapDao()
            if (dao.countOpenByDetector(GapDetectors.A11Y_SERVICE) == 0L) {
                dao.insert(
                    CoverageGapEntity(
                        detector = GapDetectors.A11Y_SERVICE,
                        startedAtMs = System.currentTimeMillis(),
                        endedAtMs = null,
                        state = GapState.ACTIVE,
                        note = note,
                    )
                )
            }
        }
        submitAsync { refreshStatusBlocking() }
    }

    // ------------------------------------------------------------------
    // 迁移与健康检查
    // ------------------------------------------------------------------

    fun runLegacyMigrationIfNeeded(context: Context) {
        submitAsync {
            LegacyT03Migrator.run(context.applicationContext, requireDb())
            drainPendingParse()
            refreshStatusBlocking()
        }
    }

    /** 短信差量回填（V1.1 §3.2）：仅当 READ_SMS 已授权时执行。 */
    fun backfillSms(context: Context) {
        submitAsync {
            SmsBackfill.run(context.applicationContext)
            refreshStatusBlocking()
        }
    }

    fun drainPendingParse() {
        submitAsync {
            val dao = requireDb().observationDao()
            while (true) {
                val batch = dao.pendingParse(100)
                if (batch.isEmpty()) break
                batch.forEach { CandidatePromoter.process(requireDb(), it.id) }
            }
            refreshStatusBlocking()
        }
    }

    /** M3 历史回扫；内容哈希与解析器版本游标保证无变化时重复执行处理量为 0。 */
    fun runDebtDiscovery() {
        submitAsync {
            DebtAccountDiscoverer.drain(requireDb(), ::accountHash)
            refreshStatusBlocking()
        }
    }

    // ------------------------------------------------------------------
    // M5 事件驱动行为学习
    // ------------------------------------------------------------------

    /**
     * 一个无障碍片段只创建一条 A11Y 原始证据和一条候选交易。
     * sourceKey 使用独立片段 ID；相同金额和接近时间从不参与跨片段去重。
     */
    fun enqueueBehaviorSignal(signal: BehaviorSignal) {
        val staged = try {
            requireNotNull(behaviorOutbox).stage(signal)
        } catch (failure: Throwable) {
            recordAsyncFailure(failure)
            return
        }
        behaviorExecutor.submit {
            try {
                ingestBehaviorSignalBlocking(signal)
                runCatching { requireNotNull(behaviorOutbox).complete(staged) }
                    .onFailure(::recordAsyncFailure)
            } catch (failure: Throwable) {
                recordAsyncFailure(failure)
            }
        }
    }

    /** 无障碍回调返回前等待独立短事务提交；不经过可能含历史回扫的共享队列。 */
    fun persistBehaviorSignal(signal: BehaviorSignal): Boolean {
        val staged = runCatching { requireNotNull(behaviorOutbox).stage(signal) }
            .onFailure(::recordAsyncFailure)
            .getOrNull()
        val committed = try {
            behaviorExecutor.submit<BehaviorCandidateEntity?> {
                ingestBehaviorSignalBlocking(signal)
            }.get()
            true
        } catch (failure: Throwable) {
            recordAsyncFailure(failure)
            false
        }
        if (committed) {
            staged?.let { file ->
                runCatching { requireNotNull(behaviorOutbox).complete(file) }
                    .onFailure(::recordAsyncFailure)
            }
            return true
        }
        // 数据库暂不可写但待办已 fsync：回调可以安全返回，由启动恢复重放。
        return staged != null
    }

    fun drainBehaviorOutbox() {
        behaviorExecutor.submit {
            try {
                requireNotNull(behaviorOutbox).pending().forEach { pending ->
                    ingestBehaviorSignalBlocking(pending.signal)
                    requireNotNull(behaviorOutbox).complete(pending.file)
                }
            } catch (failure: Throwable) {
                recordAsyncFailure(failure)
                markA11yDisconnected("行为片段待办恢复失败")
            }
        }
    }

    fun markA11yConnected() {
        markA11yHeartbeat()
        scheduleA11yGapClose()
    }

    /**
     * 敏感模式恢复的提交屏障：心跳必须先同步落盘，调用方随后才可清除恢复会话。
     * 不用 apply，避免进程在两个 SharedPreferences 写入之间死亡后留下“会话已清、心跳丢失”。
     */
    fun markA11yConnectedDurably(): Boolean {
        val context = appContext ?: return false
        val persisted = runCatching {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putLong(PREF_LAST_A11Y_HEARTBEAT_AT, System.currentTimeMillis())
                .commit()
        }.getOrDefault(false)
        if (!persisted) return false
        scheduleA11yGapClose()
        return true
    }

    private fun scheduleA11yGapClose() {
        runCatching {
            behaviorExecutor.submit {
                runCatching {
                    requireDb().coverageGapDao().closeOpenByDetector(
                        GapDetectors.A11Y_SERVICE,
                        System.currentTimeMillis(),
                    )
                    refreshStatusBlocking()
                }.onFailure(::recordAsyncFailure)
            }
        }.onFailure(::recordAsyncFailure)
    }

    fun markA11yHeartbeat() {
        appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            ?.edit()?.putLong(PREF_LAST_A11Y_HEARTBEAT_AT, System.currentTimeMillis())?.apply()
    }

    fun isA11yHeartbeatFresh(maxAgeMs: Long = 30 * 60 * 1000L): Boolean {
        val context = appContext ?: return false
        val last = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getLong(PREF_LAST_A11Y_HEARTBEAT_AT, 0L)
        return last > 0 && System.currentTimeMillis() - last in 0..maxAgeMs
    }

    fun markA11yDisconnected(note: String) {
        appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            ?.edit()?.putLong(PREF_LAST_A11Y_HEARTBEAT_AT, 0L)?.apply()
        behaviorExecutor.submit {
            runCatching {
                val dao = requireDb().coverageGapDao()
                if (dao.countOpenByDetector(GapDetectors.A11Y_SERVICE) == 0L) {
                    dao.insert(
                        CoverageGapEntity(
                            detector = GapDetectors.A11Y_SERVICE,
                            startedAtMs = System.currentTimeMillis(),
                            endedAtMs = null,
                            state = GapState.ACTIVE,
                            note = note,
                        )
                    )
                }
                refreshStatusBlocking()
            }.onFailure(::recordAsyncFailure)
        }
    }

    /** 专用高优先级执行器直接持久化，不让无障碍主回调等待共享队列或抛超时。 */
    private fun ingestBehaviorSignalBlocking(signal: BehaviorSignal): BehaviorCandidateEntity? {
        val now = System.currentTimeMillis()
        val kindText = if (signal.kind == BehaviorKind.REFUND) "退款成功" else "支付成功"
        val amountText = signal.amountCents?.let { cents ->
            "%d.%02d元".format(cents / 100, kotlin.math.abs(cents % 100))
        } ?: "金额待确认"
        val title = "行为识别：$kindText"
        val body = "$kindText $amountText ${signal.featureSummary}"
        val notificationAvailable = appContext?.let(BehaviorCandidateNotifier::isAvailableForAuto) == true
        val inserted = run {
            var created: BehaviorCandidateEntity? = null
            requireDb().runInTransaction {
                val db = requireDb()
                val behaviorDao = db.behaviorDao()
                // DB 已提交但 outbox 尚未删除时，重放必须连 duplicate_count 都不改变。
                if (behaviorDao.findSignalReceipt(signal.occurrenceId) != null) {
                    return@runInTransaction
                }
                val observation = RawObservationEntity(
                    source = ObservationSource.A11Y,
                    sourceKey = signal.clipId,
                    userHandle = 0,
                    packageName = signal.packageName,
                    postTimeMs = signal.occurredAtMs,
                    receivedAtMs = now,
                    title = title,
                    body = body,
                    contentHash = sha256Hex(title + "\u0000" + body),
                    capturePath = CapturePath.A11Y,
                    parseState = ParseState.PARSED,
                    createdAtMs = now,
                )
                val outcome = db.observationDao().ingest(observation)
                val receiptInserted = behaviorDao.insertSignalReceiptIgnore(
                    BehaviorSignalReceiptEntity(
                        occurrenceId = signal.occurrenceId,
                        observationId = outcome.id,
                        ambiguousRepeat = signal.ambiguousRepeat,
                        appliedAtMs = now,
                    )
                )
                check(receiptInserted != -1L) { "behavior occurrence receipt race" }
                if (outcome is IngestOutcome.New) {
                    val canonicalId = db.canonicalDao().createCandidateWithEvidence(
                        tx = CanonicalTransactionEntity(
                            strongIdHash = null,
                            type = if (signal.kind == BehaviorKind.REFUND) TxType.REFUND else TxType.PAYMENT,
                            status = TxStatus.DETECTED,
                            amountCents = signal.amountCents,
                            merchantHint = null,
                            occurredAtMs = signal.occurredAtMs,
                            backfilledFrom = null,
                            createdAtMs = now,
                        ),
                        observationId = outcome.id,
                        matchReason = "A11Y_BEHAVIOR_CLIP",
                        nowMs = now,
                    )
                    behaviorDao.insertTemplateIgnore(
                        BehaviorTemplateEntity(
                            templateKey = signal.templateKey,
                            packageName = signal.packageName,
                            kind = signal.kind,
                            routeSignature = signal.routeSignature,
                            appVersionCode = signal.appVersionCode,
                            positiveCount = 0,
                            negativeCount = 0,
                            consecutivePositiveCount = 0,
                            autoEnabled = false,
                            createdAtMs = now,
                            updatedAtMs = now,
                        )
                    )
                    val template = behaviorDao.findTemplate(signal.templateKey)
                    val autoRecord = BehaviorLearningPolicy.mayAutoRecord(
                        template,
                        signal,
                        notificationAvailable,
                    )
                    val candidate = BehaviorCandidateEntity(
                        publicId = signal.clipId,
                        observationId = outcome.id,
                        canonicalTxId = canonicalId,
                        templateKey = signal.templateKey,
                        packageName = signal.packageName,
                        kind = signal.kind,
                        amountCents = signal.amountCents,
                        occurredAtMs = signal.occurredAtMs,
                        confidence = signal.confidence,
                        consumedIntent = signal.consumedIntent,
                        routeSignature = signal.routeSignature,
                        appVersionCode = signal.appVersionCode,
                        ambiguousRepeatCount = if (signal.ambiguousRepeat) 1 else 0,
                        featureSummary = signal.featureSummary,
                        purpose = null,
                        state = if (autoRecord) {
                            BehaviorCandidateState.AUTO_RECORDED
                        } else {
                            BehaviorCandidateState.PENDING
                        },
                        createdAtMs = now,
                        updatedAtMs = now,
                        decidedAtMs = now.takeIf { autoRecord },
                    )
                    val candidateId = behaviorDao.insertCandidateIgnore(candidate)
                    if (candidateId != -1L) {
                        if (signal.ambiguousRepeat) {
                            behaviorDao.suspendTemplateForAmbiguity(signal.templateKey, now)
                            openAmbiguousRepeatGap(db, now)
                        }
                        if (autoRecord) {
                            db.canonicalDao().updateVerification(
                                id = canonicalId,
                                type = if (signal.kind == BehaviorKind.REFUND) TxType.REFUND else TxType.PAYMENT,
                                status = TxStatus.SUCCESS,
                                amountCents = signal.amountCents,
                                purpose = null,
                            )
                            behaviorDao.insertDecision(
                                BehaviorDecisionEntity(
                                    candidateId = candidateId,
                                    decision = BehaviorDecision.AUTO_RECORD,
                                    actor = BehaviorDecisionActor.MODEL,
                                    kind = signal.kind,
                                    amountCents = signal.amountCents,
                                    purpose = null,
                                    createdAtMs = now,
                                )
                            )
                        }
                        created = candidate.copy(id = candidateId)
                    }
                } else {
                    db.observationDao().updateParseState(outcome.id, ParseState.PARSED)
                    if (signal.ambiguousRepeat) {
                        db.behaviorDao().incrementAmbiguousRepeat(outcome.id, now)
                        db.behaviorDao().findCandidateByObservation(outcome.id)?.let { existing ->
                            db.behaviorDao().suspendTemplateForAmbiguity(existing.templateKey, now)
                        }
                        openAmbiguousRepeatGap(db, now)
                        created = db.behaviorDao().findCandidateByObservation(outcome.id)
                    }
                }
            }
            created
        }
        if (inserted != null) {
            appContext?.let { BehaviorCandidateNotifier.show(it, inserted) }
            submitAsync { refreshStatusBlocking() }
        }
        return inserted
    }

    private fun openAmbiguousRepeatGap(db: LedgerDatabase, nowMs: Long) {
        val gapDao = db.coverageGapDao()
        if (gapDao.countOpenByDetector(GapDetectors.A11Y_AMBIGUOUS_REPEAT) == 0L) {
            gapDao.insert(
                CoverageGapEntity(
                    detector = GapDetectors.A11Y_AMBIGUOUS_REPEAT,
                    startedAtMs = nowMs,
                    endedAtMs = null,
                    state = GapState.ACTIVE,
                    note = "检测到无法区分为页面刷新还是另一笔付款的重复成功终态",
                )
            )
        }
    }

    /** 通知按钮与应用内按钮共用此幂等状态机。 */
    fun applyBehaviorDecision(
        candidateId: Long,
        decision: BehaviorDecision,
        amountCents: Long? = null,
        purpose: String? = null,
    ) {
        try {
            behaviorExecutor.submit<BehaviorActionResult> {
                applyBehaviorDecisionBlocking(candidateId, decision, amountCents, purpose)
            }.get()
        } catch (failure: Throwable) {
            recordAsyncFailure(failure)
        }
    }

    fun applyBehaviorDecisionAsync(
        candidateId: Long,
        decision: BehaviorDecision,
        onComplete: () -> Unit,
    ) {
        try {
            behaviorExecutor.submit {
                try {
                    applyBehaviorDecisionBlocking(candidateId, decision, null, null)
                } catch (failure: Throwable) {
                    recordAsyncFailure(failure)
                } finally {
                    onComplete()
                }
            }
        } catch (failure: Throwable) {
            recordAsyncFailure(failure)
            onComplete()
        }
    }

    private fun applyBehaviorDecisionBlocking(
        candidateId: Long,
        decision: BehaviorDecision,
        amountCents: Long?,
        purpose: String?,
    ): BehaviorActionResult {
        val now = System.currentTimeMillis()
        val cleanPurpose = purpose?.trim()?.take(80)?.ifBlank { null }
        val result = BehaviorDecisionEngine.apply(
            db = requireDb(),
            candidateId = candidateId,
            decision = decision,
            amountCents = amountCents,
            purpose = cleanPurpose,
            nowMs = now,
        )
        if (result.changed) {
            appContext?.let { context ->
                BehaviorCandidateNotifier.cancel(context, candidateId)
                result.candidate?.takeIf { it.state == BehaviorCandidateState.AUTO_RECORDED }
                    ?.let { BehaviorCandidateNotifier.show(context, it) }
            }
            submitAsync { refreshStatusBlocking() }
        }
        return result
    }

    fun createDebugBehaviorCandidate() {
        val now = System.currentTimeMillis()
        val templateKey = sha256Hex("debug-behavior-template")
        enqueueBehaviorSignal(
            BehaviorSignal(
                occurrenceId = "debug-occurrence-${java.util.UUID.randomUUID()}",
                clipId = "debug-${java.util.UUID.randomUUID()}",
                packageName = "com.hulk.pillsapp.debug-simulator",
                kind = BehaviorKind.PAYMENT,
                amountCents = 1L,
                occurredAtMs = now,
                templateKey = templateKey,
                confidence = 95,
                consumedIntent = true,
                routeSignature = "debug-route-v1",
                appVersionCode = 1L,
                ambiguousRepeat = false,
                featureSummary = "events=2;terminal=PAYMENT_SUCCESS;amounts=1;intent=1;debug=1",
            )
        )
    }

    /** M4.0 只存入待校验账单证据，不创建正式交易或负债余额。 */
    fun commitStatementPreview(
        preview: StatementPreview,
        callback: (Result<StatementCommitResult>) -> Unit,
    ) {
        executor.submit {
            val prepared = runCatching {
                check(preview.canImport) { "账单预览未通过完整解析" }
                val now = System.currentTimeMillis()
                now to requireDb().statementDao().prepareImport(preview.toImportEntity(now))
            }
            prepared.fold(
                onSuccess = { (now, state) ->
                    if (state.duplicateCompleted) {
                        callback(
                            Result.success(
                                StatementCommitResult(state.importId, true, 0, preview.rows.size)
                            )
                        )
                    } else {
                        continueStatementImport(
                            preview = preview,
                            importId = state.importId,
                            createdAtMs = now,
                            chunkIndex = 0,
                            rowIndex = 0,
                            callback = callback,
                        )
                    }
                },
                onFailure = { failure ->
                    recordAsyncFailure(failure)
                    callback(Result.failure(failure))
                },
            )
        }
    }

    /**
     * 每次只提交一个 256 KiB 原文件块或至多 25 行，然后重新排到队尾。
     * 通知/SMS 的同步写入因此能插入批次之间，不会被十万行大事务饿死。
     */
    private fun continueStatementImport(
        preview: StatementPreview,
        importId: Long,
        createdAtMs: Long,
        chunkIndex: Int,
        rowIndex: Int,
        callback: (Result<StatementCommitResult>) -> Unit,
    ) {
        executor.submit {
            val dao = requireDb().statementDao()
            val result = runCatching {
                val totalChunks = (preview.sourceArtifact.size + STATEMENT_ARTIFACT_CHUNK_BYTES - 1) /
                    STATEMENT_ARTIFACT_CHUNK_BYTES
                when {
                    chunkIndex < totalChunks -> {
                        val start = chunkIndex * STATEMENT_ARTIFACT_CHUNK_BYTES
                        val end = minOf(start + STATEMENT_ARTIFACT_CHUNK_BYTES, preview.sourceArtifact.size)
                        val bytes = preview.sourceArtifact.copyOfRange(start, end)
                        dao.appendArtifactChunk(
                            StatementArtifactChunkEntity(
                                importId = importId,
                                chunkIndex = chunkIndex,
                                chunkHash = java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(bytes).joinToString("") { byte -> "%02x".format(byte) },
                                bytes = bytes,
                            )
                        )
                        continueStatementImport(
                            preview, importId, createdAtMs, chunkIndex + 1, rowIndex,
                            callback,
                        )
                    }
                    rowIndex < preview.rows.size -> {
                        val end = minOf(rowIndex + STATEMENT_IMPORT_ROW_BATCH_SIZE, preview.rows.size)
                        dao.appendRowBatch(
                            importId,
                            preview.toRowEntities(createdAtMs, rowIndex, end),
                        )
                        continueStatementImport(
                            preview, importId, createdAtMs, chunkIndex, end,
                            callback,
                        )
                    }
                    else -> {
                        dao.finalizeImport(importId, totalChunks, preview.rows.size)
                        refreshStatusBlocking()
                        callback(
                            Result.success(
                                StatementCommitResult(importId, false, preview.rows.size, 0)
                            )
                        )
                    }
                }
            }
            result.exceptionOrNull()?.let { failure ->
                runCatching { dao.markFailed(importId) }
                recordAsyncFailure(failure)
                refreshStatusBlocking()
                callback(Result.failure(failure))
            }
        }
    }

    fun markHealthCheck() {
        appContext?.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            ?.edit()?.putLong(PREF_LAST_HEALTH_CHECK_AT, System.currentTimeMillis())?.apply()
    }

    fun refreshStatusAsync() {
        submitAsync { refreshStatusBlocking() }
    }

    private fun refreshStatusBlocking() {
        val context = appContext ?: return
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val db = requireDb()
        val statementImports = db.statementDao().listImports()
        val sourceCoverage = SourceCoverageAudit.build(
            db.observationDao().sourceCoverageSummaries(),
            statementImports,
        )
        val snapshot = KernelStatus(
            observationCount = db.observationDao().countAll(),
            candidateCount = db.canonicalDao().countAll(),
            pendingParseCount = db.observationDao().countPendingParse(),
            openGapCount = db.coverageGapDao().countOpen(),
            openGaps = db.coverageGapDao().openGaps(),
            debtAccountCount = db.debtAccountDao().countVisible(),
            suspectedDebtCount = db.debtAccountDao().countByStatus(DebtAccountStatus.SUSPECTED),
            identifiedDebtCount = db.debtAccountDao().countByStatus(DebtAccountStatus.IDENTIFIED),
            baselinedDebtCount = db.debtAccountDao().countByStatus(DebtAccountStatus.BASELINED),
            reconcilableDebtCount = db.debtAccountDao().countByStatus(DebtAccountStatus.RECONCILABLE),
            conflictedDebtCount = db.debtAccountDao().countByStatus(DebtAccountStatus.CONFLICTED),
            debtDiscoveryPendingCount = db.debtAccountDao()
                .countPendingDiscovery(DEBT_DISCOVERY_PARSER_VERSION),
            repaymentsAwaitingBaselineCount = db.debtAccountDao().countRepaymentsAwaitingBaseline(),
            eligibleRevisionCount = db.debtAccountDao().countEligibleRevisions(),
            discoveryScannedCount = db.debtAccountDao().countCurrentScans(),
            discoveryFailedCount = db.debtAccountDao().countFailedScans(),
            debtEvidenceCount = db.debtAccountDao().countCurrentEvidence(),
            orphanDebtEvidenceCount = db.debtAccountDao().countOrphanEvidence(),
            duplicateDebtSignalCount = db.debtAccountDao().countDuplicateSignalFingerprints(),
            duplicateConfirmedIdentityCount = db.debtAccountDao().countDuplicateConfirmedIdentities(),
            nonAuthoritativeBaselineCount = db.debtAccountDao()
                .countStatusWithoutAuthoritativeEvidence(DebtAccountStatus.BASELINED),
            nonAuthoritativeReconcilableCount = db.debtAccountDao()
                .countStatusWithoutAuthoritativeEvidence(DebtAccountStatus.RECONCILABLE),
            kernelAsyncFailureCount = if (
                java.io.File(context.filesDir, "kernel_async_failure.txt").exists()
            ) {
                1L
            } else {
                0L
            },
            coverageSourceCount = sourceCoverage.count { it.isObservationSource }.toLong(),
            sourcesWithoutStatementCount = sourceCoverage.count {
                it.isObservationSource &&
                    (it.statementObservedRowFromMs == null || it.statementObservedRowToMs == null)
            }.toLong(),
            statementImportCount = db.statementDao().countImports(),
            statementRowCount = db.statementDao().countRows(),
            statementAwaitingValidationCount = db.statementDao().countAwaitingValidation(),
            statementIncompleteImportCount = db.statementDao().countIncompleteImports(),
            statementIntegrityFailureCount = db.statementDao().countCompletedIntegrityFailures(),
            orphanStatementLinkCount = db.statementDao().countOrphanLinks(),
            behaviorPendingCount = db.behaviorDao().countByState(BehaviorCandidateState.PENDING),
            behaviorConfirmedCount = db.behaviorDao().countByState(BehaviorCandidateState.CONFIRMED),
            behaviorAutoRecordedCount = db.behaviorDao().countByState(BehaviorCandidateState.AUTO_RECORDED),
            behaviorAutoTemplateCount = db.behaviorDao().countAutoTemplates(),
            behaviorDecisionCount = db.behaviorDao().countDecisions(),
            behaviorSignalReceiptCount = db.behaviorDao().countSignalReceipts(),
            orphanBehaviorSignalReceiptCount = db.behaviorDao().countOrphanSignalReceipts(),
            orphanBehaviorCount = db.behaviorDao().countOrphans(),
            a11yHeartbeatFresh = isA11yHeartbeatFresh(),
            behaviorCandidates = db.behaviorDao().listRecent(),
            sourceCoverage = sourceCoverage,
            statementImports = statementImports,
            debtAccounts = db.debtAccountDao().listVisible(),
            lastSweepAtMs = prefs.getLong(PREF_LAST_SWEEP_AT, 0L).takeIf { it > 0L },
            t03Migrated = prefs.getBoolean(PREF_T03_MIGRATED, false),
        )
        _status.value = snapshot
        // 调试通道：只写计数快照，不含任何原文（T00 §5），供 ADB 真机验证读取。
        try {
            java.io.File(context.filesDir, "kernel_status_snapshot.txt").writeText(
                buildString {
                    appendLine("schema_version=5")
                    appendLine("debt_parser_version=$DEBT_DISCOVERY_PARSER_VERSION")
                    appendLine("statement_parser_version=$STATEMENT_PARSER_VERSION")
                    appendLine("observations=${snapshot.observationCount}")
                    appendLine("eligible_revisions=${snapshot.eligibleRevisionCount}")
                    appendLine("candidates=${snapshot.candidateCount}")
                    appendLine("pending_parse=${snapshot.pendingParseCount}")
                    appendLine("open_gaps=${snapshot.openGapCount}")
                    snapshot.openGaps.forEachIndexed { index, gap ->
                        appendLine("open_gap_${index}_detector=${gap.detector}")
                    }
                    appendLine("a11y_heartbeat_fresh=${snapshot.a11yHeartbeatFresh}")
                    appendLine("debt_accounts=${snapshot.debtAccountCount}")
                    appendLine("debt_suspected=${snapshot.suspectedDebtCount}")
                    appendLine("debt_identified=${snapshot.identifiedDebtCount}")
                    appendLine("debt_baselined=${snapshot.baselinedDebtCount}")
                    appendLine("debt_reconcilable=${snapshot.reconcilableDebtCount}")
                    appendLine("debt_conflicted=${snapshot.conflictedDebtCount}")
                    appendLine("debt_discovery_pending=${snapshot.debtDiscoveryPendingCount}")
                    appendLine("repayments_awaiting_baseline=${snapshot.repaymentsAwaitingBaselineCount}")
                    appendLine("debt_discovery_scanned=${snapshot.discoveryScannedCount}")
                    appendLine("debt_discovery_failed=${snapshot.discoveryFailedCount}")
                    appendLine("debt_evidence=${snapshot.debtEvidenceCount}")
                    appendLine("orphan_debt_evidence=${snapshot.orphanDebtEvidenceCount}")
                    appendLine("duplicate_debt_signals=${snapshot.duplicateDebtSignalCount}")
                    appendLine("duplicate_confirmed_identity=${snapshot.duplicateConfirmedIdentityCount}")
                    appendLine("non_authoritative_baselines=${snapshot.nonAuthoritativeBaselineCount}")
                    appendLine("non_authoritative_reconcilable=${snapshot.nonAuthoritativeReconcilableCount}")
                    appendLine("kernel_async_failures=${snapshot.kernelAsyncFailureCount}")
                    appendLine("coverage_sources=${snapshot.coverageSourceCount}")
                    appendLine("sources_without_statement=${snapshot.sourcesWithoutStatementCount}")
                    appendLine("statement_imports=${snapshot.statementImportCount}")
                    appendLine("statement_rows=${snapshot.statementRowCount}")
                    appendLine("statement_awaiting_validation=${snapshot.statementAwaitingValidationCount}")
                    appendLine("statement_incomplete_imports=${snapshot.statementIncompleteImportCount}")
                    appendLine("statement_integrity_failures=${snapshot.statementIntegrityFailureCount}")
                    appendLine("orphan_statement_links=${snapshot.orphanStatementLinkCount}")
                    appendLine("behavior_pending=${snapshot.behaviorPendingCount}")
                    appendLine("behavior_confirmed=${snapshot.behaviorConfirmedCount}")
                    appendLine("behavior_auto_recorded=${snapshot.behaviorAutoRecordedCount}")
                    appendLine("behavior_auto_templates=${snapshot.behaviorAutoTemplateCount}")
                    appendLine("behavior_decisions=${snapshot.behaviorDecisionCount}")
                    appendLine("behavior_signal_receipts=${snapshot.behaviorSignalReceiptCount}")
                    appendLine("orphan_behavior_signal_receipts=${snapshot.orphanBehaviorSignalReceiptCount}")
                    appendLine("orphan_behavior=${snapshot.orphanBehaviorCount}")
                    appendLine("t03_migrated=${snapshot.t03Migrated}")
                    appendLine("snapshot_at_ms=${System.currentTimeMillis()}")
                }
            )
        } catch (_: Throwable) {
            // 快照写盘失败不影响主流程
        }
    }

    private fun recordAsyncFailure(failure: Throwable) {
        appContext?.let { context ->
            runCatching {
                java.io.File(context.filesDir, "kernel_async_failure.txt").writeText(
                    "type=${failure.javaClass.name}\nat_ms=${System.currentTimeMillis()}\n"
                )
            }
        }
    }

    internal fun markT03Migrated(context: Context) {
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit().putBoolean(PREF_T03_MIGRATED, true).apply()
    }

    internal fun isT03Migrated(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_T03_MIGRATED, false)
}
