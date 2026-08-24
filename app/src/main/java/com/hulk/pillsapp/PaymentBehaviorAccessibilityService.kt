package com.hulk.pillsapp

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.hulk.pillsapp.ledger.BEHAVIOR_WINDOW_MAX_EVENTS
import com.hulk.pillsapp.ledger.BEHAVIOR_WINDOW_MS
import com.hulk.pillsapp.ledger.BehaviorTextClassifier
import com.hulk.pillsapp.ledger.BehaviorEpisodeTracker
import com.hulk.pillsapp.ledger.BehaviorDebugEvidenceStore
import com.hulk.pillsapp.ledger.PackagePaymentSequenceTracker
import com.hulk.pillsapp.ledger.BehaviorWindowFeature
import com.hulk.pillsapp.ledger.GapDetectors
import com.hulk.pillsapp.ledger.LedgerKernel
import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 事件驱动采集：不遍历节点树。页面原文只在本次回调内用于分类，随后即丢弃；用户主动
 * 开启调试证据后，疑似候选触发瞬间可保存一张 Keystore 加密、七天过期的本地截图。
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
    private val packageSequenceTracker = PackagePaymentSequenceTracker()
    private var windowPackageName: String? = null
    private val sensitiveRegistryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sensitive-registry-events").apply { isDaemon = true }
    }
    private val evidenceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "behavior-debug-evidence").apply { isDaemon = true }
    }
    private val sensitiveNoticeExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(32),
        { runnable -> Thread(runnable, "sensitive-notice-events").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private val registryRefreshQueued = AtomicBoolean(false)
    private val guardWorkGate = SensitiveGuardWorkGate()
    private val warningWorkGate = SensitiveTimedWorkGate(30_000L)
    private val unsafeNoticePackages = mutableSetOf<String>()

    override fun onCreate() {
        super.onCreate()
        // 固化 Service 实例所属的恢复尝试；后续重试不能被这个旧实例误确认。
        restoreAttemptAtCreate = SensitiveAppMode.currentRestoreAttemptId(this)
        // 预热只发生在独立线程；系统回调随后只读已验证的内存身份快照。
        scheduleRegistryRefresh()
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
        val sensitiveLookup = SensitiveAppRegistry.lookupFast(packageName)
        if (sensitiveLookup.kind == SensitiveProfileLookupKind.NOT_READY) {
            // 冷启动、解密失败或包身份刚失效时必须 fail-closed：不读取任何事件文本，
            // 后台重建完成后才可能把这个包判为普通应用。
            window.clear()
            episodeTracker.onContext(packageName, event.windowId, now)
            val actions = guardWorkGate.enterGuard(packageName, now, needsRefresh = true)
            if (actions.openGap) {
                LedgerKernel.openGapAsync(
                    sensitiveProfileGuardDetector(packageName),
                    "敏感应用身份快照尚未就绪：事件文本未读取，需以权威账单补核",
                    now,
                )
            }
            actions.refreshToken?.let { scheduleGuardedRegistryRefresh(packageName, it) }
            return
        }
        val sensitiveProfile = sensitiveLookup.profile
        if (sensitiveLookup.kind == SensitiveProfileLookupKind.ACTIVE && sensitiveProfile != null) {
            // 直接启动银行时不读取事件文本；银行会将无障碍误报为录屏，引导下次安全重开。
            window.clear()
            episodeTracker.onContext(packageName, event.windowId, now)
            val actions = guardWorkGate.enterGuard(packageName, now, needsRefresh = false)
            if (actions.openGap) unsafeNoticePackages.remove(packageName)
            if (actions.openGap) {
                LedgerKernel.openGapAsync(
                    sensitiveProfileGuardDetector(packageName),
                    "已确认敏感应用被直接打开：为避免安全警告未读取事件文本",
                    now,
                )
            }
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                unsafeNoticePackages.add(packageName)
            ) {
                val submitted = runCatching {
                    sensitiveNoticeExecutor.execute {
                        SensitiveAppMode.notifyUnsafeLaunch(applicationContext, sensitiveProfile)
                    }
                    true
                }.getOrDefault(false)
                if (!submitted) unsafeNoticePackages.remove(packageName)
            }
            return
        }
        if (guardWorkGate.leaveGuard(packageName)) {
            LedgerKernel.closeOpenGapsAsync(sensitiveProfileGuardDetector(packageName), now)
        }
        if (windowPackageName != packageName) {
            window.clear()
            windowPackageName = packageName
        }
        prune(now)
        episodeTracker.onContext(packageName, event.windowId, now)

        // 不读取 event.source，也不保存原文；只使用事件随附的短文本完成本次分类。
        val texts = buildList {
            event.text.orEmpty().forEach { add(it?.toString().orEmpty()) }
            event.contentDescription?.toString()?.let(::add)
        }.filter { it.isNotBlank() }
        SensitiveWarningClassifier.classify(texts)?.let { warning ->
            // 回调内只留下脱敏后的包名与风险枚举；Keystore、fsync、签名解析和通知
            // 全部在独立串行线程完成，避免阻塞随后到达的付款事件。
            val workKey = "$packageName|${warning.name}"
            if (warningWorkGate.tryStart(workKey, now)) {
                val submitted = runCatching {
                    sensitiveNoticeExecutor.execute {
                        try {
                            SensitiveAppRegistry.propose(applicationContext, packageName, warning)
                                ?.let { suggestion ->
                                    if (SensitiveAppRegistry.isCurrentSuggestion(applicationContext, suggestion)) {
                                        SensitiveModeNotifier.showSuggestion(applicationContext, suggestion)
                                    }
                                }
                        } finally {
                            warningWorkGate.finish(workKey, System.currentTimeMillis())
                        }
                    }
                    true
                }.getOrDefault(false)
                if (!submitted) warningWorkGate.cancel(workKey)
            }
        }
        val packageSequence = packageSequenceTracker.observe(
            packageName = packageName,
            texts = texts,
            nowMs = now,
            windowStateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
        if (!BehaviorTextClassifier.isSupportedBehaviorPackage(packageName)) {
            window.clear()
            windowPackageName = null
            return
        }
        val intent = packageSequence.intent || (
            event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
                BehaviorTextClassifier.hasPaymentIntent(texts)
            )
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

        val terminal = packageSequence.terminal ?: BehaviorTextClassifier.terminal(texts)
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
        if (!episodeTracker.hasRecentIntent(packageName, now) &&
            packageSequence.terminal == null &&
            !BehaviorTextClassifier.allowsTerminalWithoutIntent(packageName)
        ) return
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
        captureDebugEvidence(signal.clipId)
    }

    override fun onInterrupt() {
        // AccessibilityService.onInterrupt 只要求停止语音/震动等反馈，并不表示解绑。
        // 本服务没有可中断反馈；在线状态只能由 onDestroy/心跳超时判定。
    }

    override fun onDestroy() {
        connectionAccepted = false
        heartbeatHandler.removeCallbacks(heartbeat)
        sensitiveRegistryExecutor.shutdown()
        sensitiveNoticeExecutor.shutdown()
        evidenceExecutor.shutdown()
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

    private fun captureDebugEvidence(publicId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            !BehaviorDebugEvidenceStore.isEnabled(this)
        ) return
        captureDebugEvidenceApi30(publicId)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun captureDebugEvidenceApi30(publicId: String) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            evidenceExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val buffer = screenshot.hardwareBuffer
                    try {
                        val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return
                        val software = wrapped.copy(Bitmap.Config.ARGB_8888, false) ?: return
                        try {
                            BehaviorDebugEvidenceStore.save(
                                this@PaymentBehaviorAccessibilityService,
                                publicId,
                                software,
                            )
                        } finally {
                            software.recycle()
                        }
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // FLAG_SECURE、系统策略或瞬时无可用窗口时安全退化；候选本身仍正常保存。
                }
            },
        )
    }

    private fun scheduleRegistryRefresh() {
        if (!registryRefreshQueued.compareAndSet(false, true)) return
        val submitted = runCatching {
            sensitiveRegistryExecutor.execute {
                try {
                    SensitiveAppRegistry.profiles(applicationContext)
                } finally {
                    registryRefreshQueued.set(false)
                }
            }
            true
        }.getOrDefault(false)
        if (!submitted) registryRefreshQueued.set(false)
    }

    private fun scheduleGuardedRegistryRefresh(packageName: String, refreshToken: Long) {
        // 不与普通预热合并：本任务必须排在上面的 gap-open 提交之后。这样校验成功时
        // close 一定晚于 open；若解密失败或该包身份仍无效，缺口保持活动。
        if (!registryRefreshQueued.compareAndSet(false, true)) {
            guardWorkGate.finishRefresh(
                packageName,
                refreshToken,
                resolved = false,
                nowMs = System.currentTimeMillis(),
            )
            return
        }
        val submitted = runCatching {
            sensitiveRegistryExecutor.execute {
                try {
                    val resolved = runCatching {
                        SensitiveAppRegistry.profiles(applicationContext)
                        SensitiveAppRegistry.storageHealthy(applicationContext) &&
                            SensitiveAppRegistry.lookupFast(packageName).kind != SensitiveProfileLookupKind.NOT_READY
                    }.getOrDefault(false)
                    val closeGap = guardWorkGate.finishRefresh(
                        packageName,
                        refreshToken,
                        resolved,
                        System.currentTimeMillis(),
                    )
                    if (closeGap) {
                        runCatching {
                            LedgerKernel.closeOpenGaps(sensitiveProfileGuardDetector(packageName))
                        }
                    }
                } finally {
                    registryRefreshQueued.set(false)
                }
            }
            true
        }.getOrDefault(false)
        if (!submitted) {
            registryRefreshQueued.set(false)
            guardWorkGate.finishRefresh(
                packageName,
                refreshToken,
                resolved = false,
                nowMs = System.currentTimeMillis(),
            )
        }
    }

    private fun packageVersionCode(packageName: String): Long = runCatching {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
    }.getOrDefault(-1L)
}

internal fun sensitiveProfileGuardDetector(packageName: String): String =
    "${GapDetectors.A11Y_PROFILE_GUARD}:${sha256Hex(packageName).take(16)}"

internal data class SensitiveGuardActions(
    val openGap: Boolean,
    val refreshToken: Long?,
)

/** 每包 gap + 全局单刷新门控；所有方法只改小型内存状态，不做 I/O。 */
internal class SensitiveGuardWorkGate(
    private val retryBackoffMs: Long = 30_000L,
) {
    private data class PackageState(
        var gapOpenSubmitted: Boolean = false,
        var closeProbeNeeded: Boolean = true,
        var refreshToken: Long? = null,
        var retryNotBeforeMs: Long = 0L,
    )

    private val states = mutableMapOf<String, PackageState>()
    private var globalRefreshToken: Long? = null
    private var nextToken = 0L

    @Synchronized
    fun enterGuard(packageName: String, nowMs: Long, needsRefresh: Boolean): SensitiveGuardActions {
        val state = states.getOrPut(packageName, ::PackageState)
        val openGap = !state.gapOpenSubmitted
        if (openGap) {
            state.gapOpenSubmitted = true
            state.closeProbeNeeded = false
        }
        val refreshToken = if (needsRefresh && globalRefreshToken == null &&
            state.refreshToken == null && nowMs >= state.retryNotBeforeMs
        ) {
            (++nextToken).also {
                state.refreshToken = it
                globalRefreshToken = it
            }
        } else {
            null
        }
        return SensitiveGuardActions(openGap, refreshToken)
    }

    @Synchronized
    fun finishRefresh(
        packageName: String,
        refreshToken: Long,
        resolved: Boolean,
        nowMs: Long,
    ): Boolean {
        val state = states[packageName] ?: return false
        if (state.refreshToken != refreshToken || globalRefreshToken != refreshToken) return false
        state.refreshToken = null
        globalRefreshToken = null
        if (!resolved) {
            state.retryNotBeforeMs = nowMs + retryBackoffMs
            return false
        }
        state.retryNotBeforeMs = 0L
        val closeGap = state.gapOpenSubmitted || state.closeProbeNeeded
        state.gapOpenSubmitted = false
        state.closeProbeNeeded = false
        return closeGap
    }

    @Synchronized
    fun leaveGuard(packageName: String): Boolean {
        val state = states.getOrPut(packageName, ::PackageState)
        val closeGap = state.gapOpenSubmitted || state.closeProbeNeeded
        state.gapOpenSubmitted = false
        state.closeProbeNeeded = false
        return closeGap
    }
}

/** 高频风险页的 package+kind 合并门控；有界执行器拒绝任务时可立即释放。 */
internal class SensitiveTimedWorkGate(private val backoffMs: Long) {
    private data class State(var inFlight: Boolean = false, var retryNotBeforeMs: Long = 0L)
    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun tryStart(key: String, nowMs: Long): Boolean {
        val state = states.getOrPut(key, ::State)
        if (state.inFlight || nowMs < state.retryNotBeforeMs) return false
        state.inFlight = true
        return true
    }

    @Synchronized
    fun finish(key: String, nowMs: Long) {
        val state = states[key] ?: return
        state.inFlight = false
        state.retryNotBeforeMs = nowMs + backoffMs
    }

    @Synchronized
    fun cancel(key: String) {
        states[key]?.inFlight = false
    }
}
