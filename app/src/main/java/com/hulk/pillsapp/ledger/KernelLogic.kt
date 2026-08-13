package com.hulk.pillsapp.ledger

import com.hulk.pillsapp.sha256Hex
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 纯逻辑内核：不依赖 Android/Room，可在 JVM 单元测试中直接验证。
 * 规则来源：V1 §5（唯一性与去重）、V1.1 §4/§5（写入管线与阶段划分）。
 */

/** 同一 (source, sourceKey) 下新事件的处理决策，见 V1 §5.4。 */
enum class IngestDecision { NEW, DUPLICATE, REVISION }

fun decideIngest(existingContentHash: String?, newContentHash: String): IngestDecision = when {
    existingContentHash == null -> IngestDecision.NEW
    existingContentHash == newContentHash -> IngestDecision.DUPLICATE
    else -> IngestDecision.REVISION
}

enum class SignalDirection { EXPENSE, INCOME, REFUND, UNKNOWN }

data class ParsedSignal(
    val amountCents: Long,
    val direction: SignalDirection,
    val strongId: String?,
)

/**
 * 实时层金融信号解析。
 * 只产出候选信号，绝不跨来源合并（V1.1 §5）；强标识仅用于同来源修订/证据合并。
 */
object FinancialSignalParser {
    private val amountPattern = Regex(
        """[¥￥]\s*(\d+(?:,\d{3})*(?:\.\d{1,2})?)|(\d+(?:,\d{3})*(?:\.\d{1,2})?)\s*元"""
    )
    private val refundWords = Regex("""退款|退回|返还|refund""", RegexOption.IGNORE_CASE)
    private val expenseWords = Regex("""支付|付款|扣款|消费|支出|缴费""")
    private val incomeWords = Regex("""收款|到账|入账|存入|工资""")
    private val financialContext = Regex(
        """支付|付款|扣款|消费|退款|到账|入账|收款|交易|余额|账单|银行|商户|order|refund""",
        RegexOption.IGNORE_CASE,
    )
    private val labeledIdPattern = Regex(
        """(?:订单号?|交易号|流水号|商户单号|单号|order\s*(?:no|id)?|txn\s*id)[:：\s]*([A-Za-z0-9]{10,32})""",
        RegexOption.IGNORE_CASE,
    )
    // Java 默认 \w 为 ASCII，因此 \b 在中英文混排文本中对数字串仍然有效。
    private val bareIdPattern = Regex("""\b(\d{16,32})\b""")

    fun parse(title: String, body: String): ParsedSignal? {
        val text = "$title\n$body"
        if (!financialContext.containsMatchIn(text)) return null
        val amountMatch = amountPattern.find(text) ?: return null
        val rawNumber = amountMatch.groupValues[1].ifEmpty { amountMatch.groupValues[2] }
        val amountCents = BigDecimal(rawNumber.replace(",", ""))
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()
        val direction = when {
            refundWords.containsMatchIn(text) -> SignalDirection.REFUND
            expenseWords.containsMatchIn(text) -> SignalDirection.EXPENSE
            incomeWords.containsMatchIn(text) -> SignalDirection.INCOME
            else -> SignalDirection.UNKNOWN
        }
        val strongId = labeledIdPattern.find(text)?.groupValues?.get(1)
            ?: bareIdPattern.find(text)?.groupValues?.get(1)
        return ParsedSignal(amountCents = amountCents, direction = direction, strongId = strongId)
    }
}

/** 强标识只存不可逆摘要（V1 §5.2）；带 source 前缀避免跨渠道误并。 */
fun strongIdHash(
    source: ObservationSource,
    sourceNamespace: String,
    userHandle: Int,
    idKind: String,
    strongId: String,
): String = sha256Hex("${source.name}:$sourceNamespace:$userHandle:$idKind:$strongId")

/**
 * V1 §5.1 禁令的实现级表述：只有双方都带相同强标识摘要时才允许自动合并候选。
 * 金额相同、商户相似、时间接近一律返回 false。
 */
fun mayAutoMergeCandidates(strongIdHashA: String?, strongIdHashB: String?): Boolean =
    strongIdHashA != null && strongIdHashA == strongIdHashB

// ---------------------------------------------------------------------------
// 跨来源集合匹配（V1 §5.3）：对账层使用，实时层禁止调用。
// ---------------------------------------------------------------------------

data class SideRecord(
    val id: String,
    val amountCents: Long,
    val timeMs: Long,
    val cardTail: String? = null,
)

data class Pairing(val leftId: String, val rightId: String)

data class PairingResult(
    val pairs: List<Pairing>,
    val unmatchedLeftIds: List<String>,
    val unmatchedRightIds: List<String>,
    val conflictedIds: Set<String>,
)

object CrossSourcePairer {
    private fun compatible(a: SideRecord, b: SideRecord, maxTimeSkewMs: Long): Boolean {
        if (a.amountCents != b.amountCents) return false
        if (kotlin.math.abs(a.timeMs - b.timeMs) > maxTimeSkewMs) return false
        if (a.cardTail != null && b.cardTail != null && a.cardTail != b.cardTail) return false
        return true
    }

    /**
     * 仅当一对候选在双方集合中互为唯一匹配时才自动配对；
     * 任何多对多/一对多形态整体进入冲突集合，不允许任意选择（V1 §5.3、§10 用例 11）。
     */
    fun pair(left: List<SideRecord>, right: List<SideRecord>, maxTimeSkewMs: Long): PairingResult {
        val pairs = ArrayList<Pairing>()
        val conflicted = LinkedHashSet<String>()
        for (l in left) {
            val matches = right.filter { compatible(l, it, maxTimeSkewMs) }
            when (matches.size) {
                0 -> Unit
                1 -> {
                    val r = matches[0]
                    val backMatches = left.filter { compatible(it, r, maxTimeSkewMs) }
                    if (backMatches.size == 1 && backMatches[0].id == l.id) {
                        pairs += Pairing(l.id, r.id)
                    } else {
                        conflicted += l.id
                        conflicted += r.id
                    }
                }
                else -> {
                    conflicted += l.id
                    matches.forEach { conflicted += it.id }
                }
            }
        }
        val pairedLeft = pairs.map { it.leftId }.toSet()
        val pairedRight = pairs.map { it.rightId }.toSet()
        return PairingResult(
            pairs = pairs,
            unmatchedLeftIds = left.map { it.id }.filter { it !in pairedLeft && it !in conflicted },
            unmatchedRightIds = right.map { it.id }.filter { it !in pairedRight && it !in conflicted },
            conflictedIds = conflicted,
        )
    }
}
