package com.hulk.pillsapp

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.hulk.pillsapp.ledger.BEHAVIOR_WINDOW_MAX_EVENTS
import com.hulk.pillsapp.ledger.BEHAVIOR_WINDOW_MS
import com.hulk.pillsapp.ledger.BehaviorTextClassifier
import com.hulk.pillsapp.ledger.BehaviorEpisodeTracker
import com.hulk.pillsapp.ledger.BehaviorWindowFeature
import com.hulk.pillsapp.ledger.LedgerKernel
import java.util.ArrayDeque

/**
 * 事件驱动采集：不截图、不遍历节点树。页面原文只在本次回调内用于分类，随后即丢弃。
 */
class PaymentBehaviorAccessibilityService : AccessibilityService() {
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            LedgerKernel.markA11yHeartbeat()
            heartbeatHandler.postDelayed(this, 15 * 60 * 1000L)
        }
    }
    private val window = ArrayDeque<BehaviorWindowFeature>()
    private val episodeTracker = BehaviorEpisodeTracker { "a11y-${java.util.UUID.randomUUID()}" }

    override fun onServiceConnected() {
        super.onServiceConnected()
        BehaviorAccessibilityState.setConnected(this, true)
        LedgerKernel.markA11yConnected()
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == applicationContext.packageName) return
        val now = System.currentTimeMillis()
        prune(now)
        episodeTracker.onContext(packageName, event.windowId, now)

        // 不读取 event.source，也不保存原文；只使用事件随附的短文本完成本次分类。
        val texts = buildList {
            event.text.orEmpty().forEach { add(it?.toString().orEmpty()) }
            event.contentDescription?.toString()?.let(::add)
        }.filter { it.isNotBlank() }
        val intent = event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            BehaviorTextClassifier.hasPaymentIntent(texts)
        val classHash = sha256Hex(event.className?.toString().orEmpty()).take(12)
        val role = when {
            intent -> "INTENT_CLICK"
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "WINDOW"
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> "CLICK"
            event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "TEXT"
            else -> "CONTENT"
        }
        window.addLast(
            BehaviorWindowFeature(
                atMs = now,
                packageName = packageName,
                eventRole = role,
                classHashPrefix = classHash,
                hasPaymentIntent = intent,
            )
        )
        while (window.size > BEHAVIOR_WINDOW_MAX_EVENTS) window.removeFirst()

        val terminal = BehaviorTextClassifier.terminal(texts)
        if (!intent && terminal == null) return
        // 每个意图/终态都重新读取；支付 App 热更新后旧版本模板会立即失效。
        val appVersionCode = packageVersionCode(packageName)
        if (intent) {
            episodeTracker.onIntent(
                packageName = packageName,
                nowMs = now,
                routeSignature = BehaviorTextClassifier.routeSignature(
                    packageName,
                    appVersionCode,
                    texts,
                    window.toList(),
                ),
                appVersionCode = appVersionCode,
            )
        }
        terminal ?: return
        val terminalIdentity = listOf(
            terminal.kind.name,
            terminal.amountCents?.toString().orEmpty(),
            terminal.terminalCode,
            classHash,
        ).joinToString("|")
        val emission = episodeTracker.onTerminal(
            packageName,
            event.windowId,
            terminalIdentity,
            now,
            appVersionCode,
        )
        val signal = BehaviorTextClassifier.buildSignal(
            clipId = emission.clipId,
            packageName = packageName,
            occurredAtMs = now,
            terminal = terminal,
            features = window.toList(),
            emission = emission,
        )
        if (!LedgerKernel.persistBehaviorSignal(signal)) {
            episodeTracker.onPersistenceFailed(
                packageName,
                event.windowId,
                terminalIdentity,
                now,
                emission,
            )
            throw IllegalStateException("behavior signal could not be persisted")
        }
    }

    override fun onInterrupt() {
        BehaviorAccessibilityState.setConnected(this, false)
        heartbeatHandler.removeCallbacks(heartbeat)
        LedgerKernel.markA11yDisconnected("行为学习无障碍服务已中断")
    }

    override fun onDestroy() {
        BehaviorAccessibilityState.setConnected(this, false)
        heartbeatHandler.removeCallbacks(heartbeat)
        LedgerKernel.markA11yDisconnected("行为学习无障碍服务已停止")
        super.onDestroy()
    }

    private fun prune(now: Long) {
        while (window.isNotEmpty() && now - window.first().atMs > BEHAVIOR_WINDOW_MS) {
            window.removeFirst()
        }
        episodeTracker.prune(now)
    }

    private fun packageVersionCode(packageName: String): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }.getOrDefault(-1L)
}
