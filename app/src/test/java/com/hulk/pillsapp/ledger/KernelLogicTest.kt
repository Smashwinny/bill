package com.hulk.pillsapp.ledger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 内核纯逻辑测试，覆盖 V1 §10 与 V1.1 §9 中可在 JVM 层验证的部分。
 * 数据库约束行为（唯一索引、INSERT OR IGNORE 冲突计数）在真机阶段抽样复核。
 */
class KernelLogicTest {
    @Test
    fun durableObservationSchedulesParseBeforeOutboxCompletionFailure() {
        val events = mutableListOf<String>()
        val failure = runCatching {
            DurableObservationCommitOrder.commit(
                ingest = {
                    events += "db-committed"
                    IngestOutcome.New(42L)
                },
                scheduleProcessing = {
                    events += "parse-scheduled-${it.id}"
                },
                completeOutbox = {
                    events += "outbox-delete-fsync"
                    error("injected directory fsync failure")
                },
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertEquals(
            listOf("db-committed", "parse-scheduled-42", "outbox-delete-fsync"),
            events,
        )
    }

    @Test
    fun observationRecoveryRequiresOutboxAndPendingParseBothEmpty() {
        assertTrue(ObservationRecoveryRules.isComplete(false, 0L))
        assertFalse(ObservationRecoveryRules.isComplete(true, 0L))
        assertFalse(ObservationRecoveryRules.isComplete(false, 1L))
        assertFalse(ObservationRecoveryRules.isComplete(true, 1L))
    }

    @Test
    fun durableCallbackNeverWaitsForDatabaseOnSystemMainThread() {
        assertFalse(CallbackPersistencePolicy.shouldWaitForDatabase(stagedDurably = true))
        assertTrue(CallbackPersistencePolicy.shouldWaitForDatabase(stagedDurably = false))
    }

    @Test
    fun rejectedRetryScheduleNeverThrowsOrLocksTheFlag() {
        val scheduled = java.util.concurrent.atomic.AtomicBoolean(false)
        val failures = mutableListOf<Throwable>()
        val rejected = RetryScheduleGuard.scheduleNoThrow(
            scheduled = scheduled,
            schedule = { throw java.util.concurrent.RejectedExecutionException("injected") },
            retry = { error("must not run") },
            onFailure = failures::add,
        )
        assertFalse(rejected)
        assertFalse(scheduled.get())
        assertEquals(1, failures.size)

        var runnable: Runnable? = null
        var retried = false
        val accepted = RetryScheduleGuard.scheduleNoThrow(
            scheduled = scheduled,
            schedule = { runnable = it },
            retry = { retried = true },
            onFailure = failures::add,
        )
        assertTrue(accepted)
        assertTrue(scheduled.get())
        requireNotNull(runnable).run()
        assertTrue(retried)
        assertFalse(scheduled.get())
    }

    // ---------------------------------------------------------------
    // V1 §5.4 / V1.1 §4：同 key 投递决策
    // ---------------------------------------------------------------

    @Test
    fun ingestDecisionNewKeyIsNew() {
        assertEquals(IngestDecision.NEW, decideIngest(null, "hash-a"))
    }

    @Test
    fun ingestDecisionSameKeySameHashIsDuplicate() {
        // V1 §10 用例 4：完全相同回调重复投递 → 不增加交易，只计数。
        assertEquals(IngestDecision.DUPLICATE, decideIngest("hash-a", "hash-a"))
    }

    @Test
    fun ingestDecisionSameKeyDifferentHashIsRevision() {
        // V1 §10 用例 3：处理中 → 成功 属修订，不新增观察行。
        assertEquals(IngestDecision.REVISION, decideIngest("hash-a", "hash-b"))
    }

    // ---------------------------------------------------------------
    // 金额与方向解析
    // ---------------------------------------------------------------

    @Test
    fun parseExpenseWithYuanSuffix() {
        val signal = FinancialSignalParser.parse("支付成功", "你已通过支付宝付款 12.34 元")
        assertEquals(1234L, signal?.amountCents)
        assertEquals(SignalDirection.EXPENSE, signal?.direction)
    }

    @Test
    fun parseRefundWithCurrencySymbol() {
        val signal = FinancialSignalParser.parse("退款通知", "退款成功 ¥5.00，原路退回")
        assertEquals(500L, signal?.amountCents)
        assertEquals(SignalDirection.REFUND, signal?.direction)
    }

    @Test
    fun parseAmountWithThousandsSeparator() {
        val signal = FinancialSignalParser.parse("扣款提醒", "尾号 8888 账户扣款 1,234.56 元")
        assertEquals(123456L, signal?.amountCents)
    }

    @Test
    fun parseNonFinancialTextReturnsNull() {
        assertNull(FinancialSignalParser.parse("天气提醒", "明天晴转多云"))
    }

    @Test
    fun parseFinancialContextWithoutAmountReturnsNull() {
        // 只有关键词没有金额：不足以形成候选，但也不能丢（由 PENDING_PARSE 兜底重试）。
        assertNull(FinancialSignalParser.parse("交易提醒", "请查看你的账单"))
    }

    // ---------------------------------------------------------------
    // 强标识（V1 §5.2）
    // ---------------------------------------------------------------

    @Test
    fun labeledOrderIdIsExtracted() {
        val signal = FinancialSignalParser.parse("支付成功", "订单号 2026081200001234567890123456，付款 9.90 元")
        assertEquals("2026081200001234567890123456", signal?.strongId)
    }

    @Test
    fun strongIdHashIsSourceScoped() {
        val notificationHash = strongIdHash(ObservationSource.NOTIFICATION, "com.example.pay", 0, "ORDER_ID", "1234567890123456")
        val smsHash = strongIdHash(ObservationSource.SMS, "95555", 0, "BANK_SERIAL", "1234567890123456")
        // 同一订单号在不同来源不自动合并：跨来源配对只走对账层集合匹配（V1.1 §5）。
        assertNotEquals(notificationHash, smsHash)
        assertEquals(64, notificationHash.length)
    }

    @Test
    fun sameNumericIdFromDifferentAppsNeverSharesStrongHash() {
        val first = strongIdHash(ObservationSource.NOTIFICATION, "com.example.pay.a", 0, "ORDER_ID", "1234567890123456")
        val second = strongIdHash(ObservationSource.NOTIFICATION, "com.example.pay.b", 0, "ORDER_ID", "1234567890123456")
        assertNotEquals(first, second)
    }

    @Test
    fun weakSimilarityNeverAutoMerges() {
        // V1 §10 用例 2：同商户同金额两笔，无双强标识 → 永不自动合并。
        assertFalse(mayAutoMergeCandidates(null, null))
        assertFalse(mayAutoMergeCandidates("hash-a", null))
        assertTrue(mayAutoMergeCandidates("hash-a", "hash-a"))
        assertFalse(mayAutoMergeCandidates("hash-a", "hash-b"))
    }

    // ---------------------------------------------------------------
    // 跨来源集合匹配（V1 §5.3、§10 用例 11/12）
    // ---------------------------------------------------------------

    private fun record(id: String, cents: Long, timeMs: Long, tail: String? = null) =
        SideRecord(id = id, amountCents = cents, timeMs = timeMs, cardTail = tail)

    @Test
    fun uniqueOneToOnePairingSucceeds() {
        val left = listOf(record("alipay-1", 1000, 100_000))
        val right = listOf(record("bank-1", 1000, 100_000 + 30_000))
        val result = CrossSourcePairer.pair(left, right, maxTimeSkewMs = 60_000)
        assertEquals(listOf(Pairing("alipay-1", "bank-1")), result.pairs)
        assertTrue(result.conflictedIds.isEmpty())
    }

    @Test
    fun ambiguousSameAmountPairsGoToConflictNotMerged() {
        // V1 §10 用例 11：支付侧两条 10 元、银行侧两条 10 元 → 全部进冲突，不聚成一笔。
        val left = listOf(record("pay-a", 1000, 100_000), record("pay-b", 1000, 105_000))
        val right = listOf(record("bank-a", 1000, 100_500), record("bank-b", 1000, 105_500))
        val result = CrossSourcePairer.pair(left, right, maxTimeSkewMs = 60_000)
        assertTrue(result.pairs.isEmpty())
        assertEquals(setOf("pay-a", "pay-b", "bank-a", "bank-b"), result.conflictedIds)
    }

    @Test
    fun cardTailMismatchBlocksPairing() {
        val left = listOf(record("pay-1", 1000, 100_000, tail = "1234"))
        val right = listOf(record("bank-1", 1000, 100_000, tail = "9999"))
        val result = CrossSourcePairer.pair(left, right, maxTimeSkewMs = 60_000)
        assertTrue(result.pairs.isEmpty())
        assertEquals(listOf("pay-1"), result.unmatchedLeftIds)
        assertEquals(listOf("bank-1"), result.unmatchedRightIds)
    }

    @Test
    fun authorityShortageLeavesExtraCandidatesUnmatched() {
        // V1 §10 用例 12：权威账单少于观察候选 → 多余候选保持未匹配，不消失。
        val left = listOf(record("pay-1", 1000, 100_000), record("pay-2", 2000, 200_000))
        val right = listOf(record("bank-1", 1000, 100_000))
        val result = CrossSourcePairer.pair(left, right, maxTimeSkewMs = 60_000)
        assertEquals(1, result.pairs.size)
        assertEquals(listOf("pay-2"), result.unmatchedLeftIds)
    }

    @Test
    fun timeSkewBeyondWindowBlocksPairing() {
        val left = listOf(record("pay-1", 1000, 100_000))
        val right = listOf(record("bank-1", 1000, 100_000 + 3_600_000))
        val result = CrossSourcePairer.pair(left, right, maxTimeSkewMs = 60_000)
        assertTrue(result.pairs.isEmpty())
        assertEquals(listOf("pay-1"), result.unmatchedLeftIds)
    }
}
