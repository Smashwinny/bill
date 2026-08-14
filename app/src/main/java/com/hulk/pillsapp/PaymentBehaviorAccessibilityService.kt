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
    private var restoreAttemptAtCreate: String? = null
    private var connectionAccepted = false
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            when (BehaviorAccessibilityState.claimCallbackOwner(
                this@PaymentBehaviorAccessibilityService,
                this@PaymentBehaviorAccessibilityService,
            )) {
                CallbackOwnerAccess.RECOVERED -> {
                    LedgerKernel.markA11yConnected()
                }
                CallbackOwnerAccess.CURRENT -> LedgerKernel.markA11yHeartbeat()
                CallbackOwnerAccess.STALE -> return
            }
            heartbeatHandler.postDelayed(this, 15 * 60 * 1000L)
        }
    }
    private val window = ArrayDeque<BehaviorWindowFeature>()
    private val episodeTracker = BehaviorEpisodeTracker { "a11y-${java.util.UUID.randomUUID()}" }

    override fun onCreate() {
        super.onCreate()
        // 固化 Service 实例所属的恢复尝试；后续重试不能被这个旧实例误确认。
        restoreAttemptAtCreate = SensitiveAppMode.currentRestoreAttemptId(this)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val confirmation = SensitiveAppMode.confirmServiceConnected(this, restoreAttemptAtCreate) {
            BehaviorAccessibilityState.registerCallbackOwner(this, this)
            if (!LedgerKernel.markA11yConnectedDurably()) {
                BehaviorAccessibilityState.clearCallbackConnected(this, this)
                false
            } else {
                connectionAccepted = true
                true
            }
        }
        if (!confirmation.allowsCollection()) {
            connectionAccepted = false
            return
        }
        restoreAttemptAtCreate = null
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connectionAccepted) return
        event ?: return
        // 包升级时旧 Service.onDestroy 可能晚于新实例的 onServiceConnected；
        // 事件可认领回调所有权，并只在真正恢复时关闭持久缺口。
        when (BehaviorAccessibilityState.claimCallbackOwner(this, this)) {
            CallbackOwnerAccess.RECOVERED -> {
                LedgerKernel.markA11yConnected()
            }
            CallbackOwnerAccess.CURRENT -> Unit
            CallbackOwnerAccess.STALE -> return
        }
        val packageName = event.packageName?.toString().orEmpty()
        if (packageName.isBlank() || packageName == applicationContext.packageName) return
        val now = System.currentTimeMillis()
        if (SensitiveAppMode.isSensitivePackage(packageName)) {
            // 直接启动银行时不读取事件文本；银行会将无障碍误报为录屏，引导下次安全重开。
            window.clear()
            episodeTracker.onContext(packageName, event.windowId, now)
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                SensitiveAppMode.notifyUnsafeCmbLaunch(this)
            }
            return
        }
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
        // AccessibilityService.onInterrupt 只要求停止语音/震动等反馈，并不表示解绑。
        // 本服务没有可中断反馈；在线状态只能由 onDestroy/心跳超时判定。
    }

    override fun onDestroy() {
        connectionAccepted = false
        heartbeatHandler.removeCallbacks(heartbeat)
        if (BehaviorAccessibilityState.clearCallbackConnected(this, this)) {
            LedgerKernel.markA11yDisconnected(
                SensitiveAppMode.activeDisconnectNote(this)
                    ?: "行为学习无障碍服务已停止"
            )
        }
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
