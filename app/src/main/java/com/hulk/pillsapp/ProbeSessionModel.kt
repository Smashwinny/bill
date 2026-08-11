package com.hulk.pillsapp

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

enum class ProbeScenario {
    FOREGROUND,
    BACKGROUND,
    LOCK_SCREEN,
}

enum class ProbeAction {
    SUCCESS_PAYMENT,
    FULL_REFUND,
    PARTIAL_REFUND,
}

data class ProbeFieldPresence(
    val hasAmount: Boolean,
    val hasMerchantHint: Boolean,
    val hasOrderHint: Boolean,
    val hasExplicitSuccessOrRefund: Boolean,
)

data class ProbeSessionConfig(
    val sessionId: String,
    val channelName: String,
    val packageName: String,
    val scenario: ProbeScenario,
    val action: ProbeAction,
    val startedAtMs: Long,
)

data class ProbeSessionObservation(
    val notificationKeyHash: String,
    val postedAtMs: Long,
    val receivedAtMs: Long,
    val reusedNotificationKey: Boolean,
    val fieldPresence: ProbeFieldPresence,
)

data class ProbeSessionResult(
    val config: ProbeSessionConfig,
    val endedAtMs: Long,
    val observations: List<ProbeSessionObservation>,
) {
    fun receivedAnyNotification(): Boolean = observations.isNotEmpty()

    fun coverageText(): String {
        if (!receivedAnyNotification()) return "未收到通知"
        val count = observations.size
        val reused = observations.count { it.reusedNotificationKey }
        return "收到通知；共 $count 条，key复用 $reused 条"
    }

    fun amountCoverage(): String = if (!receivedAnyNotification()) "0/0" else {
        "${observations.count { it.fieldPresence.hasAmount }}/${observations.size}"
    }

    fun merchantCoverage(): String = if (!receivedAnyNotification()) "0/0" else {
        "${observations.count { it.fieldPresence.hasMerchantHint }}/${observations.size}"
    }

    fun orderCoverage(): String = if (!receivedAnyNotification()) "0/0" else {
        "${observations.count { it.fieldPresence.hasOrderHint }}/${observations.size}"
    }

    fun semanticCoverage(): String = if (!receivedAnyNotification()) "0/0" else {
        "${observations.count { it.fieldPresence.hasExplicitSuccessOrRefund }}/${observations.size}"
    }
}

data class ProbeReportBundle(
    val sessions: List<ProbeSessionResult>,
    val generatedAtMs: Long = System.currentTimeMillis(),
    val appVersionName: String,
    val appVersionCode: Int,
)

private val timestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

private fun formatMsToLocalTime(millis: Long): String = Instant.ofEpochMilli(millis)
    .atZone(ZoneId.systemDefault())
    .format(timestampFormatter)

fun analyzeProbeFields(title: String, body: String): ProbeFieldPresence {
    val text = listOf(title, body).joinToString("\u0000").lowercase()

    val amountPattern = Regex("""(?:[¥￥]\s*\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s*元)""")
    val merchantPattern = Regex("""(商户|门店|收款方|付款方|商家|merchant|shop|支付店|收银)""")
    val orderPattern = Regex("""(订单|单号|order|txn|交易号|trans(?:action)?\s*id)""")
    val successOrRefundPattern = Regex("""(支付成功|已支付|付款成功|交易成功|退款成功|已退款|部分退款|全额退款|reversed|refund(?:\s*success)?)""")

    return ProbeFieldPresence(
        hasAmount = amountPattern.containsMatchIn(text),
        hasMerchantHint = merchantPattern.containsMatchIn(text),
        hasOrderHint = orderPattern.containsMatchIn(text),
        hasExplicitSuccessOrRefund = successOrRefundPattern.containsMatchIn(text),
    )
}

private fun hash(value: String): String = sha256Hex(value).take(10)

class ProbeSessionRuntime(
    val config: ProbeSessionConfig,
) {
    private val observations = ArrayList<ProbeSessionObservation>()
    fun appendEvent(event: NotificationEvent, reusedNotificationKey: Boolean) {
        if (event.packageName != config.packageName) return
        if (event.receivedAtMs < config.startedAtMs) return

        val fields = analyzeProbeFields(event.title, event.body)
        observedInsert(event, fields, reusedNotificationKey)
    }

    private fun observedInsert(event: NotificationEvent, fields: ProbeFieldPresence, reusedNotificationKey: Boolean) {
        observations.add(
            ProbeSessionObservation(
                notificationKeyHash = hash(event.notificationKey),
                postedAtMs = event.postedAtMs,
                receivedAtMs = event.receivedAtMs,
                reusedNotificationKey = reusedNotificationKey,
                fieldPresence = fields,
            )
        )
    }

    fun finish(endedAtMs: Long): ProbeSessionResult = ProbeSessionResult(
        config = config,
        endedAtMs = endedAtMs,
        observations = observations.toList(),
    )
}

object ProbeSessionRepository {
    private val lock = Any()
    private val _activeSession = kotlinx.coroutines.flow.MutableStateFlow<ProbeSessionConfig?>(null)
    private val _completedSessions = kotlinx.coroutines.flow.MutableStateFlow<List<ProbeSessionResult>>(emptyList())
    private var activeRuntime: ProbeSessionRuntime? = null
    private var sequence = AtomicLong(0L)

    val activeSessionConfig: kotlinx.coroutines.flow.StateFlow<ProbeSessionConfig?> = _activeSession
    val completedSessions: kotlinx.coroutines.flow.StateFlow<List<ProbeSessionResult>> = _completedSessions

    fun startSession(
        channelName: String,
        packageName: String,
        scenario: ProbeScenario,
        action: ProbeAction,
        startedAtMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(lock) {
            if (_activeSession.value != null) return false
            val sessionId = "t04-${sequence.incrementAndGet()}-${System.currentTimeMillis()}"
            val config = ProbeSessionConfig(
                sessionId = sessionId,
                channelName = channelName,
                packageName = packageName,
                scenario = scenario,
                action = action,
                startedAtMs = startedAtMs,
            )
            activeRuntime = ProbeSessionRuntime(config)
            _activeSession.value = config
            return true
        }
    }

    fun endSession(endedAtMs: Long = System.currentTimeMillis()): ProbeSessionResult? {
        synchronized(lock) {
            val runtime = activeRuntime ?: return null
            val snapshot = runtime.finish(endedAtMs)
            activeRuntime = null
            _activeSession.value = null
            _completedSessions.value = _completedSessions.value + snapshot
            return snapshot
        }
    }

    fun clearCompletedSessions() {
        _completedSessions.value = emptyList()
    }

    fun onNotificationEvent(event: NotificationEvent, keyReused: Boolean) {
        synchronized(lock) {
            val runtime = activeRuntime ?: return
            if (event.packageName != runtime.config.packageName) return
            runtime.appendEvent(event, keyReused)
        }
    }
}

fun buildProbeReportText(bundle: ProbeReportBundle): String {
    val lines = ArrayList<String>()
    val versionLine = "版本: ${bundle.appVersionName} (versionCode=${bundle.appVersionCode})"
    val generatedLine = "报告生成时间: ${formatMsToLocalTime(bundle.generatedAtMs)}"
    lines.add("支付渠道真机可行性探针报告")
    lines.add(versionLine)
    lines.add(generatedLine)
    lines.add("共计会话: ${bundle.sessions.size}")
    lines.add("")
    bundle.sessions.forEachIndexed { index, session ->
        val i = index + 1
        val status = if (session.receivedAnyNotification()) "有通知" else "未收到通知"
        lines.add("会话 #$i: ${session.config.channelName} / ${session.config.packageName}")
        lines.add("场景=${session.config.scenario} 动作=${session.config.action}")
        lines.add("开始=${formatMsToLocalTime(session.config.startedAtMs)} 结束=${formatMsToLocalTime(session.endedAtMs)}")
        lines.add("覆盖结果: $status")
        lines.add("字段覆盖(金额/商户/订单/语义): ${session.amountCoverage()} / ${session.merchantCoverage()} / ${session.orderCoverage()} / ${session.semanticCoverage()}")
        lines.add("通知key复用: ${session.observations.count { it.reusedNotificationKey }}")
        if (session.observations.isNotEmpty()) {
            lines.add("通知摘要摘要:")
            session.observations.forEachIndexed { eventIndex, observation ->
                val idx = eventIndex + 1
                val hashHint = observation.notificationKeyHash
                val posted = formatMsToLocalTime(observation.postedAtMs)
                val received = formatMsToLocalTime(observation.receivedAtMs)
                val sameKey = if (observation.reusedNotificationKey) "复用" else "新key"
                lines.add("  $idx) key=$hashHint pkg=${session.config.packageName} posted=$posted received=$received keyReuse=$sameKey")
            }
        } else {
            lines.add("未采集到任何通知事件（会话已结束）")
        }
        lines.add("")
    }
    return lines.joinToString("\n")
}

fun writeProbeReportToPrivateFile(
    context: Context,
    reportText: String,
    fileName: String = "t04_probe_report_${System.currentTimeMillis()}.txt",
): String {
    val dir = File(context.filesDir, "probe_reports")
    if (!dir.exists()) {
        dir.mkdirs()
    }
    val outputFile = File(dir, fileName)
    outputFile.writeText(reportText)
    return outputFile.absolutePath
}
