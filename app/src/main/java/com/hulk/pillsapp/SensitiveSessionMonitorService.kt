package com.hulk.pillsapp

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 仅在敏感会话内存活的短时前台服务。它不截图、不读取 Activity 内容，也不保存其他 App
 * 的使用历史；UsageEvents 只能触发“疑似离开，请确认”的通知，绝不自行恢复无障碍。
 */
class SensitiveSessionMonitorService : Service() {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sensitive-session-monitor").apply { isDaemon = true }
    }
    private val destroyed = AtomicBoolean(false)
    private val pollScheduled = AtomicBoolean(false)
    private val stateLock = Any()
    private var sessionId: String? = null
    private var ownerStartId: Int = 0
    private var tracker: SensitiveDepartureTracker? = null
    private var queryCursorMs = 0L
    private var permissionFailureNotified = false
    private val recentEventKeys = LinkedHashSet<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedSessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val session = SensitiveAppMode.currentSession(this)
        if (requestedSessionId.isNullOrBlank() || session?.id != requestedSessionId ||
            session.phase !in setOf(SensitiveLaunchPhase.PAUSED, SensitiveLaunchPhase.LAUNCHED)
        ) {
            // Android 可能把旧 start intent 迟到交给已经服务新会话的同一实例。
            // 这种 stale start 只能被忽略，绝不能删除新会话通知或 stopSelf。
            val activeOwnedSession = synchronized(stateLock) {
                val owned = this.sessionId
                session?.takeIf {
                    owned != null && it.id == owned &&
                        it.phase in setOf(SensitiveLaunchPhase.PAUSED, SensitiveLaunchPhase.LAUNCHED)
                }?.also {
                    // 该 stale intent 仍成为 Android 记录的最新 startId；owner 必须前移，
                    // 否则会话结束后 stopSelfResult(旧 id) 永远无法停止轮询。
                    ownerStartId = SensitiveMonitorGenerationRules.promoteForStaleStart(
                        ownedSessionId = owned,
                        currentSessionId = it.id,
                        currentStartId = ownerStartId,
                        receivedStartId = startId,
                    ) ?: ownerStartId
                }
            }
            if (activeOwnedSession != null) {
                startForeground(
                    SensitiveModeNotifier.FOREGROUND_NOTIFICATION_ID,
                    SensitiveModeNotifier.monitoringNotification(this, activeOwnedSession),
                )
                return START_NOT_STICKY
            }
            startForeground(
                SensitiveModeNotifier.ORPHAN_FOREGROUND_NOTIFICATION_ID,
                SensitiveModeNotifier.orphanedMonitorNotification(this),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        startForeground(
            SensitiveModeNotifier.FOREGROUND_NOTIFICATION_ID,
            SensitiveModeNotifier.monitoringNotification(this, session),
        )
        if (!UsageAccessState.isGranted(this)) {
            SensitiveModeNotifier.showUsageAccessUnavailable(this, session)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        // 前台服务通知已经接管“立即恢复”入口，移除暂停阶段的同 session 临时通知，
        // 避免正常运行时出现两个内容相近的常驻通知。
        SensitiveModeNotifier.cancelSessionNotice(this, session.id)
        synchronized(stateLock) {
            if (this.sessionId != requestedSessionId) {
                this.sessionId = requestedSessionId
                tracker = SensitiveDepartureTracker(
                    targetPackage = session.targetPackage,
                    targetEnteredInitially = session.targetEntered,
                    promptedSinceLastTargetInitially = session.exitPrompted,
                )
                queryCursorMs = maxOf(session.startedAtMs, System.currentTimeMillis() - INITIAL_LOOKBACK_MS)
                permissionFailureNotified = false
                recentEventKeys.clear()
            }
            ownerStartId = startId
        }
        if (pollScheduled.compareAndSet(false, true)) {
            executor.scheduleWithFixedDelay(::poll, 0L, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
        return START_NOT_STICKY
    }

    private fun poll() {
        if (destroyed.get()) return
        val generation = synchronized(stateLock) {
            Triple(sessionId ?: return, ownerStartId, tracker ?: return)
        }
        val expected = generation.first
        val expectedStartId = generation.second
        val expectedTracker = generation.third
        val session = SensitiveAppMode.currentSession(this)
        if (session?.id != expected || session.phase != SensitiveLaunchPhase.LAUNCHED) {
            stopFromWorker(expected, expectedStartId)
            return
        }
        if (!UsageAccessState.isGranted(this)) {
            if (!permissionFailureNotified) {
                permissionFailureNotified = true
                SensitiveModeNotifier.showUsageAccessUnavailable(this, session)
            }
            stopFromWorker(expected, expectedStartId)
            return
        }
        val now = System.currentTimeMillis()
        val events = runCatching {
            getSystemService(UsageStatsManager::class.java)
                .queryEvents((queryCursorMs - QUERY_OVERLAP_MS).coerceAtLeast(0L), now)
        }.getOrElse {
            if (!permissionFailureNotified) {
                permissionFailureNotified = true
                SensitiveModeNotifier.showUsageAccessUnavailable(this, session)
            }
            stopFromWorker(expected, expectedStartId)
            return
        }
        permissionFailureNotified = false
        if (events == null) {
            if (!permissionFailureNotified) {
                permissionFailureNotified = true
                SensitiveModeNotifier.showUsageAccessUnavailable(this, session)
            }
            stopFromWorker(expected, expectedStartId)
            return
        }
        val event = UsageEvents.Event()
        var maxTimestamp = queryCursorMs
        val transitions = mutableListOf<SensitiveUsageTransition>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            maxTimestamp = maxOf(maxTimestamp, event.timeStamp)
            if (event.eventType != UsageEvents.Event.ACTIVITY_RESUMED &&
                event.eventType != UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                continue
            }
            val eventKey = listOf(
                event.timeStamp,
                event.eventType,
                event.packageName.orEmpty(),
                event.className.orEmpty(),
            ).joinToString("|")
            if (!recentEventKeys.add(eventKey)) continue
            while (recentEventKeys.size > MAX_RECENT_EVENT_KEYS) {
                val oldest = recentEventKeys.iterator()
                oldest.next()
                oldest.remove()
            }
            transitions += SensitiveUsageTransition(event.timeStamp, event.packageName.orEmpty())
        }
        SensitiveUsageTransitionOrder.order(transitions, session.targetPackage).forEach { transition ->
            when (expectedTracker.observe(transition.packageName, packageName, transientPackages())) {
                SensitiveMonitorAction.TARGET_ENTERED -> {
                    if (SensitiveAppMode.markTargetEntered(this, expected)) {
                        // 相机/验证码/生物识别的“可能离开”提示在目标返回后必须撤销；
                        // 前台服务的通用人工恢复入口仍保留。
                        SensitiveModeNotifier.cancelSessionNotice(this, expected)
                    }
                }
                SensitiveMonitorAction.PROMPT_EXIT_CONFIRMATION -> {
                    SensitiveAppMode.markExitCandidate(this, expected)?.let {
                        SensitiveModeNotifier.showExitCandidate(this, it)
                    }
                }
                SensitiveMonitorAction.NONE,
                -> Unit
            }
        }
        synchronized(stateLock) {
            if (sessionId == expected && ownerStartId == expectedStartId) {
                queryCursorMs = maxOf(maxTimestamp, now - QUERY_OVERLAP_MS)
            }
        }
    }

    private fun transientPackages(): Set<String> = buildSet {
        add("android")
        add("com.android.systemui")
        add("com.android.permissioncontroller")
        add("com.google.android.permissioncontroller")
        add("com.miui.securitycenter")
    }

    private fun stopFromWorker(expectedSessionId: String, expectedStartId: Int) {
        val stillOwner = synchronized(stateLock) {
            SensitiveMonitorGenerationRules.mayStop(
                ownerSessionId = sessionId,
                ownerStartId = ownerStartId,
                expectedSessionId = expectedSessionId,
                expectedStartId = expectedStartId,
            )
        }
        if (!stillOwner) return
        // stopSelfResult 只会停止仍由该 startId 拥有的实例；较新的会话 start 已到达时返回 false。
        stopSelfResult(expectedStartId)
    }

    override fun onDestroy() {
        destroyed.set(true)
        executor.shutdownNow()
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        private const val POLL_INTERVAL_SECONDS = 1L
        private const val INITIAL_LOOKBACK_MS = 5_000L
        private const val QUERY_OVERLAP_MS = 1_500L
        private const val MAX_RECENT_EVENT_KEYS = 256

        fun start(context: Context, sessionId: String): Boolean {
            if (!UsageAccessState.isGranted(context)) return false
            val intent = Intent(context, SensitiveSessionMonitorService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            return runCatching {
                ContextCompat.startForegroundService(context.applicationContext, intent)
                true
            }.getOrDefault(false)
        }

        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(
                    Intent(context.applicationContext, SensitiveSessionMonitorService::class.java)
                )
            }
        }
    }
}

internal data class SensitiveUsageTransition(val timestampMs: Long, val packageName: String)

internal object SensitiveUsageTransitionOrder {
    fun order(
        transitions: List<SensitiveUsageTransition>,
        targetPackage: String,
    ): List<SensitiveUsageTransition> = transitions
        .groupBy { it.timestampMs }
        .toSortedMap()
        .flatMap { (_, sameTime) ->
            // 同一时间戳内只要目标自身出现，就不能用同组系统/外部 Activity 推导“已离开”。
            sameTime.filter { it.packageName == targetPackage }.ifEmpty { sameTime }
        }
}

internal object SensitiveMonitorGenerationRules {
    fun promoteForStaleStart(
        ownedSessionId: String?,
        currentSessionId: String?,
        currentStartId: Int,
        receivedStartId: Int,
    ): Int? = if (ownedSessionId != null && ownedSessionId == currentSessionId) {
        maxOf(currentStartId, receivedStartId)
    } else {
        null
    }

    fun mayStop(
        ownerSessionId: String?,
        ownerStartId: Int,
        expectedSessionId: String,
        expectedStartId: Int,
    ): Boolean = ownerSessionId == expectedSessionId && ownerStartId == expectedStartId
}
