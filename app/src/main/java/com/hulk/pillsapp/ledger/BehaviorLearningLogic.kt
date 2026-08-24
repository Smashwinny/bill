package com.hulk.pillsapp.ledger

import com.hulk.pillsapp.sha256Hex
import java.math.BigDecimal
import java.math.RoundingMode

const val BEHAVIOR_WINDOW_MS = 10_000L
const val BEHAVIOR_WINDOW_MAX_EVENTS = 80
const val BEHAVIOR_AUTO_CONFIRMATIONS = 5

data class BehaviorWindowFeature(
    val atMs: Long,
    val packageName: String,
    val eventRole: String,
    val classHashPrefix: String,
    val hasPaymentIntent: Boolean,
)

data class BehaviorTerminalMatch(
    val kind: BehaviorKind,
    val amountCents: Long?,
    val distinctAmountCount: Int,
    val terminalCode: String,
)

data class BehaviorSignal(
    val occurrenceId: String,
    val clipId: String,
    val packageName: String,
    val kind: BehaviorKind,
    val amountCents: Long?,
    val occurredAtMs: Long,
    val templateKey: String,
    val confidence: Int,
    val consumedIntent: Boolean,
    val routeSignature: String,
    val appVersionCode: Long,
    val ambiguousRepeat: Boolean,
    val featureSummary: String,
)

data class BehaviorEpisodeEmission(
    val occurrenceId: String,
    val clipId: String,
    val consumedIntent: Boolean,
    val routeSignature: String,
    val appVersionCode: Long,
    val ambiguousRepeat: Boolean = false,
)

/**
 * 一次性意图消费状态机：一个点击 episode 最多产出一个终态；新点击永远是新片段，
 * 即使金额、窗口和时间都相同。没有新点击时，同一成功页的刷新只产出一次人工候选。
 */
class BehaviorEpisodeTracker(
    private val idFactory: () -> String,
) {
    private data class Episode(
        val id: String,
        val packageName: String,
        val startedAtMs: Long,
        val routeSignature: String,
        val appVersionCode: Long,
    )

    private var episode: Episode? = null
    private var contextKey: String? = null
    private var lastContextEventAtMs: Long = 0
    private val latchedTerminals = LinkedHashMap<String, BehaviorEpisodeEmission>()

    fun onContext(packageName: String, windowId: Int, nowMs: Long) {
        val next = "$packageName|$windowId"
        if (contextKey != null && (contextKey != next || nowMs - lastContextEventAtMs > 60_000L)) {
            latchedTerminals.clear()
        }
        contextKey = next
        lastContextEventAtMs = nowMs
    }

    fun onIntent(packageName: String, nowMs: Long, routeSignature: String, appVersionCode: Long) {
        episode = Episode(idFactory(), packageName, nowMs, routeSignature, appVersionCode)
        latchedTerminals.clear()
    }

    fun onTerminal(
        packageName: String,
        windowId: Int,
        terminalIdentity: String,
        nowMs: Long,
        currentAppVersionCode: Long,
    ): BehaviorEpisodeEmission {
        onContext(packageName, windowId, nowMs)
        val latchKey = "$packageName|$windowId|$terminalIdentity"
        val current = episode?.takeIf {
            it.packageName == packageName && nowMs - it.startedAtMs in 0..BEHAVIOR_WINDOW_MS
        }
        if (current != null) {
            episode = null
            val emitted = BehaviorEpisodeEmission(
                idFactory(),
                current.id,
                true,
                current.routeSignature,
                current.appVersionCode,
            )
            putLatch(latchKey, emitted)
            return emitted
        }
        latchedTerminals[latchKey]?.let { previous ->
            return previous.copy(
                occurrenceId = idFactory(),
                consumedIntent = false,
                ambiguousRepeat = true,
            )
        }
        val emitted = BehaviorEpisodeEmission(
            idFactory(),
            idFactory(),
            false,
            "NO_CONSUMED_INTENT",
            currentAppVersionCode,
        )
        putLatch(latchKey, emitted)
        return emitted
    }

    fun prune(nowMs: Long) {
        if (episode?.let { nowMs - it.startedAtMs > BEHAVIOR_WINDOW_MS } == true) episode = null
    }

    fun onPersistenceFailed(
        packageName: String,
        windowId: Int,
        terminalIdentity: String,
        nowMs: Long,
        emission: BehaviorEpisodeEmission,
    ) {
        latchedTerminals.remove("$packageName|$windowId|$terminalIdentity")
        if (emission.consumedIntent) {
            episode = Episode(
                emission.clipId,
                packageName,
                nowMs,
                emission.routeSignature,
                emission.appVersionCode,
            )
        }
    }

    private fun putLatch(key: String, emission: BehaviorEpisodeEmission) {
        latchedTerminals[key] = emission
        while (latchedTerminals.size > 80) {
            latchedTerminals.remove(latchedTerminals.keys.first())
        }
    }
}

object BehaviorLearningPolicy {
    fun mayAutoRecord(
        template: BehaviorTemplateEntity?,
        signal: BehaviorSignal,
        notificationAvailable: Boolean,
    ): Boolean =
        notificationAvailable &&
        !signal.ambiguousRepeat &&
        template?.autoEnabled == true &&
            template.negativeCount == 0 &&
            template.consecutivePositiveCount >= BEHAVIOR_AUTO_CONFIRMATIONS &&
            signal.confidence >= 90 &&
            signal.consumedIntent &&
            signal.appVersionCode >= 0 &&
            template.appVersionCode == signal.appVersionCode &&
            template.routeSignature == signal.routeSignature &&
            signal.amountCents != null &&
            signal.amountCents > 0

    fun afterPositive(existing: BehaviorTemplateEntity, nowMs: Long): BehaviorTemplateEntity {
        val positive = existing.positiveCount + 1
        val consecutive = existing.consecutivePositiveCount + 1
        return existing.copy(
            positiveCount = positive,
            consecutivePositiveCount = consecutive,
            autoEnabled = existing.negativeCount == 0 &&
                consecutive >= BEHAVIOR_AUTO_CONFIRMATIONS,
            updatedAtMs = nowMs,
        )
    }

    fun afterNegative(existing: BehaviorTemplateEntity, nowMs: Long): BehaviorTemplateEntity =
        existing.copy(
            negativeCount = existing.negativeCount + 1,
            consecutivePositiveCount = 0,
            autoEnabled = false,
            updatedAtMs = nowMs,
        )
}

data class BehaviorActionResult(
    val changed: Boolean,
    val candidate: BehaviorCandidateEntity?,
)

internal fun shouldCloseAmbiguousRepeatGap(unresolvedCount: Long): Boolean {
    require(unresolvedCount >= 0L)
    return unresolvedCount == 0L
}

object BehaviorDecisionEngine {
    fun apply(
        db: LedgerDatabase,
        candidateId: Long,
        decision: BehaviorDecision,
        amountCents: Long?,
        purpose: String?,
        nowMs: Long,
    ): BehaviorActionResult {
        var changed = false
        var current: BehaviorCandidateEntity? = null
        db.runInTransaction {
            val behaviorDao = db.behaviorDao()
            val candidate = behaviorDao.findCandidate(candidateId) ?: return@runInTransaction
            val targetKind = when (decision) {
                BehaviorDecision.CONFIRM_PAYMENT -> BehaviorKind.PAYMENT
                BehaviorDecision.CONFIRM_REFUND -> BehaviorKind.REFUND
                else -> candidate.kind
            }
            val finalAmount = amountCents ?: candidate.amountCents
            val expected = when (decision) {
                BehaviorDecision.CONFIRM_PAYMENT,
                BehaviorDecision.CONFIRM_REFUND,
                BehaviorDecision.REJECT,
                -> BehaviorCandidateState.PENDING
                BehaviorDecision.UNDO_AUTO -> BehaviorCandidateState.AUTO_RECORDED
                BehaviorDecision.AUTO_RECORD -> return@runInTransaction
            }
            if (decision in listOf(BehaviorDecision.CONFIRM_PAYMENT, BehaviorDecision.CONFIRM_REFUND) &&
                (finalAmount == null || finalAmount <= 0)
            ) return@runInTransaction
            val targetState = when (decision) {
                BehaviorDecision.CONFIRM_PAYMENT,
                BehaviorDecision.CONFIRM_REFUND,
                -> BehaviorCandidateState.CONFIRMED
                BehaviorDecision.REJECT -> BehaviorCandidateState.REJECTED
                BehaviorDecision.UNDO_AUTO -> BehaviorCandidateState.UNDONE
                BehaviorDecision.AUTO_RECORD -> error("模型决定不走用户入口")
            }
            changed = behaviorDao.transition(
                id = candidate.id,
                expectedState = expected,
                state = targetState,
                kind = targetKind,
                amountCents = finalAmount,
                purpose = purpose ?: candidate.purpose,
                nowMs = nowMs,
            ) == 1
            if (!changed) {
                current = behaviorDao.findCandidate(candidateId)
                return@runInTransaction
            }
            val accepted = targetState == BehaviorCandidateState.CONFIRMED
            db.canonicalDao().updateVerification(
                id = candidate.canonicalTxId,
                type = if (targetKind == BehaviorKind.REFUND) TxType.REFUND else TxType.PAYMENT,
                status = if (accepted) TxStatus.SUCCESS else TxStatus.DISCARDED,
                amountCents = finalAmount,
                purpose = purpose ?: candidate.purpose,
            )
            behaviorDao.insertDecision(
                BehaviorDecisionEntity(
                    candidateId = candidate.id,
                    decision = decision,
                    actor = BehaviorDecisionActor.USER,
                    kind = targetKind,
                    amountCents = finalAmount,
                    purpose = purpose ?: candidate.purpose,
                    createdAtMs = nowMs,
                )
            )
            behaviorDao.findTemplate(candidate.templateKey)?.let { template ->
                val updated = if (accepted && targetKind == candidate.kind) {
                    BehaviorLearningPolicy.afterPositive(template, nowMs)
                } else {
                    BehaviorLearningPolicy.afterNegative(template, nowMs)
                }
                behaviorDao.updateTemplate(updated)
            }
            current = behaviorDao.findCandidate(candidateId)
        }
        return BehaviorActionResult(changed, current)
    }
}

/** 纯文本分类器；调用方不得把 [texts] 原文写日志或落库。 */
object BehaviorTextClassifier {
    private val terminalNegative = Regex("失败|取消|未成功|处理中|待支付|待付款|超时|已关闭|成功率")
    private val paymentTerminal = Regex("支付成功|付款成功|扣款成功|交易成功|消费成功|已支付|支付完成|付款完成")
    private val refundTerminal = Regex("退款成功|退款到账|已退款|退款完成")
    private val paymentIntent = Regex("确认支付|立即支付|确认付款|立即付款|收银台|支付订单|付款码|扫码付款|确认扣款")
    private val prefixedAmount = Regex("(?:¥|￥|人民币|RMB|CNY)\\s*([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
    private val suffixedAmount = Regex("([0-9]{1,9}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*元")

    fun hasPaymentIntent(texts: List<String>): Boolean =
        paymentIntent.containsMatchIn(normalize(texts))

    fun routeSignature(
        packageName: String,
        appVersionCode: Long,
        texts: List<String>,
        features: List<BehaviorWindowFeature>,
    ): String {
        val semantic = normalize(texts)
            .replace(Regex("[0-9]+(?:[.,][0-9]+)?"), "#")
            .replace(Regex("\\s+"), " ")
            .take(160)
        val path = features.filter { it.packageName == packageName }
            .takeLast(8)
            .asSequence()
            .map { "${it.eventRole}:${it.classHashPrefix}" }
            .fold(mutableListOf<String>()) { acc, item ->
                if (acc.lastOrNull() != item) acc += item
                acc
            }
            .joinToString(">")
        return sha256Hex("$packageName|$appVersionCode|$path|$semantic")
    }

    fun terminal(texts: List<String>): BehaviorTerminalMatch? {
        val text = normalize(texts)
        if (text.isBlank() || terminalNegative.containsMatchIn(text)) return null
        val match = when {
            refundTerminal.containsMatchIn(text) -> BehaviorKind.REFUND to "REFUND_SUCCESS"
            paymentTerminal.containsMatchIn(text) -> BehaviorKind.PAYMENT to "PAYMENT_SUCCESS"
            else -> return null
        }
        val amounts = extractAmounts(text)
        return BehaviorTerminalMatch(
            kind = match.first,
            amountCents = amounts.singleOrNull(),
            distinctAmountCount = amounts.size,
            terminalCode = match.second,
        )
    }

    fun buildSignal(
        clipId: String,
        packageName: String,
        occurredAtMs: Long,
        terminal: BehaviorTerminalMatch,
        features: List<BehaviorWindowFeature>,
        emission: BehaviorEpisodeEmission,
    ): BehaviorSignal {
        val recent = features
            .filter { it.packageName == packageName && occurredAtMs - it.atMs in 0..BEHAVIOR_WINDOW_MS }
            .takeLast(12)
        val hadIntent = recent.any { it.hasPaymentIntent }
        val roleSummary = recent.map { it.eventRole }.takeLast(8).joinToString(">")
        val classTail = recent.lastOrNull()?.classHashPrefix.orEmpty()
        val templateKey = sha256Hex(
            listOf(packageName, emission.appVersionCode.toString(), terminal.kind.name, emission.routeSignature, classTail)
                .joinToString("|")
        )
        val confidence = when {
            terminal.amountCents != null && emission.consumedIntent -> 95
            terminal.amountCents != null -> 82
            hadIntent -> 72
            else -> 60
        }
        return BehaviorSignal(
            occurrenceId = emission.occurrenceId,
            clipId = clipId,
            packageName = packageName,
            kind = terminal.kind,
            amountCents = terminal.amountCents,
            occurredAtMs = occurredAtMs,
            templateKey = templateKey,
            confidence = confidence,
            consumedIntent = emission.consumedIntent,
            routeSignature = emission.routeSignature,
            appVersionCode = emission.appVersionCode,
            ambiguousRepeat = emission.ambiguousRepeat,
            featureSummary = "events=${recent.size};path=$roleSummary;terminal=${terminal.terminalCode};amounts=${terminal.distinctAmountCount};consumed_intent=${if (emission.consumedIntent) 1 else 0}",
        )
    }

    private fun normalize(texts: List<String>): String = texts.asSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .take(2_048)

    private fun extractAmounts(text: String): List<Long> =
        (prefixedAmount.findAll(text).map { it.groupValues[1] } +
            suffixedAmount.findAll(text).map { it.groupValues[1] })
            .mapNotNull(::toCents)
            .filter { it > 0 }
            .distinct()
            .toList()

    private fun toCents(raw: String): Long? = runCatching {
        BigDecimal(raw.replace(",", ""))
            .setScale(2, RoundingMode.UNNECESSARY)
            .movePointRight(2)
            .longValueExact()
    }.getOrNull()
}
