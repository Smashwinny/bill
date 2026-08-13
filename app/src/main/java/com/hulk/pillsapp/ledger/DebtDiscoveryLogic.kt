package com.hulk.pillsapp.ledger

import java.math.BigDecimal
import java.math.RoundingMode

/** M3 负债账户发现纯逻辑。结果只能建立候选账户，不能直接形成正式消费或负债余额。 */
const val DEBT_DISCOVERY_PARSER_VERSION = 2

enum class DebtProduct {
    HUABEI,
    JIEBEI,
    JINGDONG_BAITIAO,
    WEILIDAI,
    MEITUAN_MONTHLY,
    DOUYIN_MONTHLY,
    CREDIT_CARD,
    CONSUMER_LOAN,
    GENERIC_LIABILITY,
}

enum class DebtAccountStatus {
    SUSPECTED,
    IDENTIFIED,
    BASELINED,
    RECONCILABLE,
    CONFLICTED,
    DORMANT,
    EXCLUDED,
}

enum class DebtEventKind {
    ACCOUNT_HINT,
    PURCHASE_ON_CREDIT,
    BILL_NOTICE,
    REPAYMENT,
    REFUND_TO_LIABILITY,
    INTEREST_OR_FEE,
    OVERDUE,
}

enum class DebtEvidenceStrength { HINT, OBSERVATIONAL, IMPORTED_UNVERIFIED, AUTHORITATIVE }

enum class DebtAmountRole {
    TOTAL_OUTSTANDING,
    CURRENT_DUE,
    MINIMUM_DUE,
    REPAYMENT_AMOUNT,
    PURCHASE_AMOUNT,
    REFUND_AMOUNT,
    INTEREST_OR_FEE,
    CREDIT_LIMIT,
    UNKNOWN,
}

enum class DiscoveryScanResult { MATCHED, NO_MATCH, FAILED }

data class DebtDiscoverySignal(
    val product: DebtProduct,
    val institutionCode: String,
    val institutionLabel: String,
    val maskedSuffix: String?,
    /** 只在内存中使用，落库仅进入账户指纹摘要。 */
    val stableAccountId: String?,
    val eventKind: DebtEventKind,
    val evidenceStrength: DebtEvidenceStrength,
    val amountRole: DebtAmountRole,
    val amountCents: Long?,
    val dueDayOfMonth: Int?,
)

data class DebtAccountIdentity(
    val clusterHash: String,
    val identityHash: String?,
    val displayLabel: String,
    val status: DebtAccountStatus,
    val confidence: Int,
)

private data class InstitutionRule(
    val code: String,
    val label: String,
    val pattern: Regex,
)

object DebtSignalParser {
    private val productRules = listOf(
        Regex("花呗") to DebtProduct.HUABEI,
        Regex("借呗") to DebtProduct.JIEBEI,
        Regex("(?:京东)?白条") to DebtProduct.JINGDONG_BAITIAO,
        Regex("微粒贷") to DebtProduct.WEILIDAI,
        Regex("美团月付") to DebtProduct.MEITUAN_MONTHLY,
        Regex("抖音月付") to DebtProduct.DOUYIN_MONTHLY,
        Regex("信用卡|贷记卡") to DebtProduct.CREDIT_CARD,
        Regex(
            "京东金条|美团借钱|抖音放心借|分期乐|360借条|度小满|有钱花|" +
                "招联(?:金融|好期贷)?|好期贷|安逸花|拿去花|任性付|唯品花|还呗|小赢卡贷|" +
                "消费贷|贷款|借款|房贷|车贷|按揭|剩余本金|应还本金",
        ) to DebtProduct.CONSUMER_LOAN,
    )

    private val institutions = listOf(
        InstitutionRule("ICBC", "工商银行", Regex("工商银行|工商行|工行|95588")),
        InstitutionRule("CCB", "建设银行", Regex("建设银行|建设行|建行|95533")),
        InstitutionRule("ABC", "农业银行", Regex("农业银行|农业行|农行|95599")),
        InstitutionRule("BOC", "中国银行", Regex("中国银行|中行|95566")),
        InstitutionRule("BCM", "交通银行", Regex("交通银行|交行|95559")),
        InstitutionRule("CMB", "招商银行", Regex("招商银行|招行|95555")),
        InstitutionRule("PSBC", "邮储银行", Regex("邮储银行|邮政储蓄|95580")),
        InstitutionRule("SPDB", "浦发银行", Regex("浦发银行|浦发|95528")),
        InstitutionRule("CITIC", "中信银行", Regex("中信银行|中信|95558")),
        InstitutionRule("CIB", "兴业银行", Regex("兴业银行|兴业|95561")),
        InstitutionRule("CMBC", "民生银行", Regex("民生银行|民生|95568")),
        InstitutionRule("PAB", "平安银行", Regex("平安银行|平安|95511")),
        InstitutionRule("CEB", "光大银行", Regex("光大银行|光大|95595")),
        InstitutionRule("HXB", "华夏银行", Regex("华夏银行|华夏|95577")),
        InstitutionRule("CGB", "广发银行", Regex("广发银行|广发|95508")),
        InstitutionRule("FENQILE", "分期乐", Regex("分期乐")),
        InstitutionRule("QIHOO360", "360借条", Regex("360借条")),
        InstitutionRule("DUXIAOMAN", "度小满", Regex("度小满|有钱花")),
        InstitutionRule("ZHAOLIAN", "招联金融", Regex("招联|好期贷")),
        InstitutionRule("MAS", "马上消费金融", Regex("马上消费|安逸花")),
        InstitutionRule("CTRIP", "携程金融", Regex("携程|拿去花")),
        InstitutionRule("SUNING", "苏宁金融", Regex("苏宁|任性付")),
        InstitutionRule("VIP", "唯品会", Regex("唯品会|唯品花")),
        InstitutionRule("DIDI", "滴滴金融", Regex("滴滴金融|滴滴借钱")),
        InstitutionRule("XIAOYING", "小赢科技", Regex("小赢|小赢卡贷")),
        InstitutionRule("HAIBEI", "还呗", Regex("还呗")),
        InstitutionRule("JD", "京东", Regex("京东|金条")),
        InstitutionRule("MEITUAN", "美团", Regex("美团")),
        InstitutionRule("DOUYIN", "抖音", Regex("抖音|放心借")),
    )

    private val accountTailPatterns = listOf(
        Regex(
            "(?:信用卡|贷记卡)(?:卡号)?[^\\n]{0,4}" +
                "(?:尾号|末四位|后四位|尾数(?:为)?)[^0-9\\n]{0,3}([0-9]{4})(?![0-9])",
        ),
        Regex(
            "(?:尾号|末四位|后四位|尾数(?:为)?)[^0-9\\n]{0,3}" +
                "([0-9]{4})(?![0-9])\\s*的?(?:信用卡|贷记卡)",
        ),
    )
    private val maskedTailPattern = Regex(
        "(?:信用卡|贷记卡)(?:卡号)?" +
            "[\\s：:（(【\\[-]{0,6}[*•·xX]{2,}([0-9]{4})(?![0-9])",
    )

    private val stableAccountIdPattern = Regex(
        "(?:合同号|借据号|贷款账户|账单账户)[：:\\s为是]*([A-Za-z0-9-]{6,40})",
        RegexOption.IGNORE_CASE,
    )

    private val eventContext = Regex("消费|付款|支付|账单|应还|待还|还款|退款|逾期|利息|手续费|本金|扣款|额度")
    private val repaymentSuccessWords = Regex("还款成功|已成功还款|成功还款|还款已到账|已偿还|扣款成功")
    private val refundWords = Regex("退款|退回|返还|冲抵")
    private val overdueWords = Regex("逾期|滞纳")
    private val feeWords = Regex("利息|手续费|服务费|逾期费|罚息")
    private val billWords = Regex("账单|本期应还|本月应还|最低还款|全部待还|总欠款|还款日|到期日")
    private val purchaseWords = Regex("消费|付款|支付")
    private val creditLimitWords = Regex("信用额度|授信额度|可用额度|总额度")
    private val marketingWords = Regex("申请|开通|邀您|立即领取|点击领取|最高可借|立即借款|提额|额度提升")
    private val actualAccountEvent = Regex(
        "消费成功|支付成功|账单已出|本期应还|本月应还|待还|最低还款|" +
            "还款成功|成功还款|还款已到账|退款成功|逾期|扣款成功",
    )
    private val genericLiabilityMarkers = listOf(
        Regex("本期账单|本期应还|本月应还|应还金额"),
        Regex("最低还款|最低应还"),
        Regex("账单日|最后还款日|到期还款日"),
        Regex("循环利息|罚息|逾期费"),
        Regex("剩余本金|应还本金|本期本金"),
    )
    private val terminalGenericLiabilityEvent = Regex(
        "还款成功|已成功还款|成功还款|还款已到账|已偿还|贷款扣收成功",
    )

    private val moneyNumber = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)"
    private val generalAmount = Regex("[¥￥]\\s*$moneyNumber|$moneyNumber\\s*元")
    private val dueDayPattern = Regex("(?:还款日|最后还款日|到期日)[^0-9]{0,12}(?:[0-9]{1,2}月)?([0-9]{1,2})日")

    fun parse(
        title: String,
        body: String,
        sourceNamespace: String,
        source: ObservationSource,
    ): DebtDiscoverySignal? = parseAll(title, body, sourceNamespace, source).firstOrNull()

    fun parseAll(
        title: String,
        body: String,
        sourceNamespace: String,
        source: ObservationSource,
    ): List<DebtDiscoverySignal> {
        val text = "$title\n$body"
        if (!eventContext.containsMatchIn(text)) return emptyList()
        val matchedProducts = productRules
            .filter { it.first.containsMatchIn(text) }
            .map { it.second }
            .distinct()
        val platformProducts = setOf(
            DebtProduct.HUABEI,
            DebtProduct.JIEBEI,
            DebtProduct.JINGDONG_BAITIAO,
            DebtProduct.WEILIDAI,
            DebtProduct.MEITUAN_MONTHLY,
            DebtProduct.DOUYIN_MONTHLY,
        )
        // 专有产品文案常同时出现“借款/贷款”，不可再额外生成一个泛贷款候选。
        val namedProducts = if (matchedProducts.any(platformProducts::contains)) {
            matchedProducts.filterNot { it == DebtProduct.CONSUMER_LOAN }
        } else {
            matchedProducts
        }
        val products = if (namedProducts.isNotEmpty()) {
            namedProducts
        } else if (qualifiesAsGenericLiability(text, source)) {
            listOf(DebtProduct.GENERIC_LIABILITY)
        } else {
            emptyList()
        }
        return products.mapNotNull { product ->
            parseProduct(product, title, body, sourceNamespace, source)
        }
    }

    private fun parseProduct(
        product: DebtProduct,
        title: String,
        body: String,
        sourceNamespace: String,
        source: ObservationSource,
    ): DebtDiscoverySignal? {
        val text = "$title\n$body"

        // 信用卡营销文案不能仅因出现“信用卡”建立账户；必须出现实际账户事件或脱敏尾号。
        // 标题和正文分别取值，禁止跨行把日期、金额或联系电话当成卡尾号。
        val tail = if (product == DebtProduct.CREDIT_CARD) {
            extractTail("$title\n$body")
        } else {
            null
        }
        val stableAccountId = stableAccountIdPattern.find(text)?.groupValues?.getOrNull(1)
        if (tail == null && stableAccountId == null && marketingWords.containsMatchIn(text) &&
            !actualAccountEvent.containsMatchIn(text)
        ) {
            return null
        }
        // 额度只是营销/账户能力，不证明已开立账户，更不能证明存在负债。
        if (tail == null && stableAccountId == null && creditLimitWords.containsMatchIn(text) &&
            !actualAccountEvent.containsMatchIn(text)
        ) {
            return null
        }
        if (product == DebtProduct.CREDIT_CARD && tail == null &&
            !Regex("消费|账单|应还|待还|还款|退款|逾期|最低还款").containsMatchIn(text)
        ) {
            return null
        }

        val eventKind = when {
            refundWords.containsMatchIn(text) -> DebtEventKind.REFUND_TO_LIABILITY
            repaymentSuccessWords.containsMatchIn(text) -> DebtEventKind.REPAYMENT
            overdueWords.containsMatchIn(text) -> DebtEventKind.OVERDUE
            feeWords.containsMatchIn(text) -> DebtEventKind.INTEREST_OR_FEE
            billWords.containsMatchIn(text) -> DebtEventKind.BILL_NOTICE
            purchaseWords.containsMatchIn(text) -> DebtEventKind.PURCHASE_ON_CREDIT
            else -> DebtEventKind.ACCOUNT_HINT
        }

        val institution = institutionFor(product, text, sourceNamespace)
        val amount = extractAmount(text, eventKind)
        val dueDay = dueDayPattern.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 1..31 }
        val strength = when {
            // 文件被选中不等于来源、账户身份、覆盖期和完整性均已验证；M4 验证后才能升级。
            source == ObservationSource.BILL_IMPORT -> DebtEvidenceStrength.IMPORTED_UNVERIFIED
            eventKind == DebtEventKind.ACCOUNT_HINT ||
                (source == ObservationSource.NOTIFICATION &&
                    !isKnownNotificationSource(product, sourceNamespace)) -> DebtEvidenceStrength.HINT
            else -> DebtEvidenceStrength.OBSERVATIONAL
        }
        return DebtDiscoverySignal(
            product = product,
            institutionCode = institution.first,
            institutionLabel = institution.second,
            maskedSuffix = tail,
            stableAccountId = stableAccountId,
            eventKind = eventKind,
            evidenceStrength = strength,
            amountRole = amount.first,
            amountCents = amount.second,
            dueDayOfMonth = dueDay,
        )
    }

    private fun qualifiesAsGenericLiability(text: String, source: ObservationSource): Boolean {
        if (source != ObservationSource.SMS && source != ObservationSource.BILL_IMPORT) return false
        val markerCount = genericLiabilityMarkers.count { it.containsMatchIn(text) }
        return markerCount >= 2 || terminalGenericLiabilityEvent.containsMatchIn(text)
    }

    private fun isKnownNotificationSource(product: DebtProduct, sourceNamespace: String): Boolean =
        when (product) {
            DebtProduct.HUABEI,
            DebtProduct.JIEBEI,
            -> sourceNamespace == "com.eg.android.AlipayGphone"
            DebtProduct.JINGDONG_BAITIAO ->
                sourceNamespace == "com.jingdong.app.mall" || sourceNamespace == "com.jd.jrapp"
            DebtProduct.WEILIDAI -> sourceNamespace == "com.tencent.mm"
            DebtProduct.MEITUAN_MONTHLY -> sourceNamespace == "com.sankuai.meituan"
            DebtProduct.DOUYIN_MONTHLY -> sourceNamespace == "com.ss.android.ugc.aweme"
            DebtProduct.CREDIT_CARD,
            DebtProduct.CONSUMER_LOAN,
            DebtProduct.GENERIC_LIABILITY,
            -> false
        }

    private fun institutionFor(
        product: DebtProduct,
        text: String,
        sourceNamespace: String,
    ): Pair<String, String> = when (product) {
        DebtProduct.HUABEI, DebtProduct.JIEBEI -> "ALIPAY" to "支付宝"
        DebtProduct.JINGDONG_BAITIAO -> "JD" to "京东"
        DebtProduct.WEILIDAI -> "WECHAT" to "微信"
        DebtProduct.MEITUAN_MONTHLY -> "MEITUAN" to "美团"
        DebtProduct.DOUYIN_MONTHLY -> "DOUYIN" to "抖音"
        DebtProduct.CREDIT_CARD,
        DebtProduct.CONSUMER_LOAN,
        DebtProduct.GENERIC_LIABILITY,
        -> institutions.firstOrNull { it.pattern.containsMatchIn("$sourceNamespace\n$text") }
            ?.let { it.code to it.label }
            ?: "UNKNOWN" to "未识别机构"
    }

    private fun extractTail(segment: String): String? {
        val tails = buildSet {
            accountTailPatterns.forEach { pattern ->
                pattern.findAll(segment).forEach { match ->
                    match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)?.let(::add)
                }
            }
            maskedTailPattern.findAll(segment).forEach { match ->
                match.groupValues.getOrNull(1)?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        return tails.singleOrNull()
    }

    private fun extractAmount(text: String, eventKind: DebtEventKind): Pair<DebtAmountRole, Long?> {
        val labeled = listOf(
            DebtAmountRole.TOTAL_OUTSTANDING to Regex("(?:全部待还|总欠款|总应付款|剩余本金)[^0-9¥￥]{0,16}[¥￥]?\\s*$moneyNumber"),
            DebtAmountRole.CURRENT_DUE to Regex("(?:本期应还|本月应还|账单金额|应还金额)[^0-9¥￥]{0,16}[¥￥]?\\s*$moneyNumber"),
            DebtAmountRole.MINIMUM_DUE to Regex("(?:最低还款|最低应还)[^0-9¥￥]{0,16}[¥￥]?\\s*$moneyNumber"),
            DebtAmountRole.CREDIT_LIMIT to Regex("(?:信用额度|授信额度|可用额度|总额度)[^0-9¥￥]{0,16}[¥￥]?\\s*$moneyNumber"),
        )
        for ((role, regex) in labeled) {
            parseMatchedAmount(regex.find(text))?.let { return role to it }
        }
        val fallbackRole = when (eventKind) {
            DebtEventKind.REPAYMENT -> DebtAmountRole.REPAYMENT_AMOUNT
            DebtEventKind.PURCHASE_ON_CREDIT -> DebtAmountRole.PURCHASE_AMOUNT
            DebtEventKind.REFUND_TO_LIABILITY -> DebtAmountRole.REFUND_AMOUNT
            DebtEventKind.INTEREST_OR_FEE, DebtEventKind.OVERDUE -> DebtAmountRole.INTEREST_OR_FEE
            else -> DebtAmountRole.UNKNOWN
        }
        return fallbackRole to parseMatchedAmount(generalAmount.find(text))
    }

    private fun parseMatchedAmount(match: MatchResult?): Long? {
        val raw = match?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() } ?: return null
        return runCatching {
            BigDecimal(raw.replace(",", ""))
                .movePointRight(2)
                .setScale(0, RoundingMode.HALF_UP)
                .longValueExact()
        }.getOrNull()
    }
}

fun buildDebtAccountIdentity(
    signal: DebtDiscoverySignal,
    userHandle: Int,
    sourceNamespace: String,
    currency: String = "CNY",
    hashMaterial: (String) -> String,
): DebtAccountIdentity {
    val sourceScope = hashMaterial("SOURCE:$sourceNamespace").take(16)
    val clusterMaterial = when {
        signal.stableAccountId != null ->
            "${signal.institutionCode}:${signal.product.name}:SOURCE:$sourceScope:" +
                "ID_HINT:${hashMaterial("ID_HINT:${signal.stableAccountId}").take(16)}"
        signal.maskedSuffix != null && signal.institutionCode != "UNKNOWN" ->
            "${signal.institutionCode}:${signal.product.name}:SOURCE:$sourceScope:" +
                "TAIL:${signal.maskedSuffix}"
        else ->
            "${signal.institutionCode}:${signal.product.name}:SOURCE:$sourceScope"
    }
    val clusterHash = hashMaterial("CLUSTER:LIABILITY:$userHandle:$currency:$clusterMaterial")
    // M3 没有来源身份验证器，任何自动提取标识都只能参与来源内候选聚类；正式身份保持空。
    val identityHash: String? = null
    val productLabel = when (signal.product) {
        DebtProduct.HUABEI -> "花呗"
        DebtProduct.JIEBEI -> "借呗"
        DebtProduct.JINGDONG_BAITIAO -> "京东白条"
        DebtProduct.WEILIDAI -> "微粒贷"
        DebtProduct.MEITUAN_MONTHLY -> "美团月付"
        DebtProduct.DOUYIN_MONTHLY -> "抖音月付"
        DebtProduct.CREDIT_CARD -> "${signal.institutionLabel}信用卡"
        DebtProduct.CONSUMER_LOAN -> "${signal.institutionLabel}贷款"
        DebtProduct.GENERIC_LIABILITY -> "${signal.institutionLabel}负债线索"
    }
    val displayLabel = signal.maskedSuffix?.let { "$productLabel（明确尾号 ••$it）" } ?: productLabel
    return DebtAccountIdentity(
        clusterHash = clusterHash,
        identityHash = identityHash,
        displayLabel = displayLabel,
        status = DebtAccountStatus.SUSPECTED,
        confidence = when {
            signal.stableAccountId != null -> 55
            signal.maskedSuffix != null && signal.institutionCode != "UNKNOWN" -> 45
            else -> 35
        },
    )
}

internal fun transitionDiscoveryStatus(
    current: DebtAccountStatus,
    observed: DebtAccountStatus,
): DebtAccountStatus = when (current) {
    // 生命周期/人工裁决状态不能被一次新观察按“等级最大值”覆盖。
    DebtAccountStatus.EXCLUDED,
    DebtAccountStatus.CONFLICTED,
    DebtAccountStatus.RECONCILABLE,
    DebtAccountStatus.BASELINED,
    -> current
    DebtAccountStatus.DORMANT -> when (observed) {
        DebtAccountStatus.IDENTIFIED,
        DebtAccountStatus.BASELINED,
        DebtAccountStatus.RECONCILABLE,
        -> observed
        else -> DebtAccountStatus.SUSPECTED
    }
    DebtAccountStatus.IDENTIFIED -> when (observed) {
        DebtAccountStatus.BASELINED,
        DebtAccountStatus.RECONCILABLE,
        -> observed
        else -> current
    }
    DebtAccountStatus.SUSPECTED -> when (observed) {
        DebtAccountStatus.IDENTIFIED,
        DebtAccountStatus.BASELINED,
        DebtAccountStatus.RECONCILABLE,
        -> observed
        else -> current
    }
}
