package com.hulk.pillsapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hulk.pillsapp.ledger.LedgerKernel
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

enum class SensitivePauseResult {
    PAUSED,
    ALREADY_PAUSED,
    TARGET_NOT_AVAILABLE,
    TARGET_NOT_CONFIRMED,
    MISSING_PRIVILEGE,
    RESTORE_NOT_SCHEDULED,
    SETTINGS_WRITE_FAILED,
    MONITOR_ALREADY_DISABLED,
    MISSING_NOTIFICATION_PERMISSION,
}

internal enum class SensitiveLaunchPhase { PREPARING, PAUSED, LAUNCHED, RECOVERING }

/** 连接是否可采集，与恢复会话是否已清理分开表达，防止清理失败时静默停采。 */
internal enum class SensitiveConnectionConfirmation {
    REJECTED,
    CONNECTED,
    CONNECTED_SESSION_PENDING,
    ;

    fun allowsCollection(): Boolean = this != REJECTED
}

internal object SensitivePhaseRules {
    fun mayLaunchTarget(phase: SensitiveLaunchPhase, ownServiceAbsent: Boolean): Boolean =
        phase == SensitiveLaunchPhase.PAUSED && ownServiceAbsent
}

internal data class SensitiveSession(
    val id: String,
    val targetPackage: String,
    val targetLabel: String,
    val targetSigningDigest: String,
    val targetVersionCode: Long,
    val targetUserSerial: Long,
    val startedAtMs: Long,
    val phase: SensitiveLaunchPhase,
    val restoreAttemptId: String?,
    val targetEntered: Boolean,
    val exitPrompted: Boolean,
)

internal object SensitiveRecoveryRules {
    fun mayConfirm(
        phase: SensitiveLaunchPhase,
        currentAttemptId: String?,
        observedAttemptId: String?,
        ownServiceEnabled: Boolean,
    ): Boolean = phase == SensitiveLaunchPhase.RECOVERING &&
        !currentAttemptId.isNullOrBlank() &&
        currentAttemptId == observedAttemptId &&
        ownServiceEnabled
}

/**
 * 个人设备上的敏感 App 安全模式。只从系统列表增删本 App 的无障碍组件，
 * 不改动其他服务；依赖 ADB 一次性授予 WRITE_SECURE_SETTINGS。
 */
object SensitiveAppMode {
    const val MAX_PAUSE_MINUTES = 15L

    private const val PREFS_NAME = "sensitive_app_mode"
    private const val KEY_ACTIVE = "active"
    private const val KEY_TARGET_PACKAGE = "target_package"
    private const val KEY_TARGET_LABEL = "target_label"
    private const val KEY_TARGET_SIGNING_DIGEST = "target_signing_digest"
    private const val KEY_TARGET_VERSION_CODE = "target_version_code"
    private const val KEY_TARGET_USER_SERIAL = "target_user_serial"
    private const val KEY_TARGET_ENTERED = "target_entered"
    private const val KEY_EXIT_PROMPTED = "exit_prompted"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_SESSION_ID = "session_id"
    private const val KEY_PHASE = "phase"
    private const val KEY_RESTORE_ATTEMPT_ID = "restore_attempt_id"
    private const val UNIQUE_RESTORE_WORK = "sensitive_app_mode_restore"
    internal const val WORK_SESSION_ID = "session_id"
    internal const val WORK_KIND = "work_kind"
    internal const val WORK_ATTEMPT_ID = "restore_attempt_id"
    internal const val RESTORE_SESSION_ID = "restore_session_id"
    internal const val WORK_KIND_DEADLINE = "deadline"
    internal const val WORK_KIND_RECOVERY_CHECK = "recovery_check"

    private val transitionLock = Any()

    fun hasControlPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    fun isActive(context: Context): Boolean = currentSession(context) != null

    fun isTargetInstalled(context: Context, packageName: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(packageName) != null

    fun pauseForPackage(context: Context, packageName: String): SensitivePauseResult {
        val profile = SensitiveAppRegistry.activeProfile(context, packageName)
            ?: return SensitivePauseResult.TARGET_NOT_CONFIRMED
        val current = PackageIdentityResolver.resolve(context, packageName)
            ?: return SensitivePauseResult.TARGET_NOT_AVAILABLE
        if (current.signingDigest != profile.identity.signingDigest ||
            current.userSerial != profile.identity.userSerial
        ) {
            return SensitivePauseResult.TARGET_NOT_CONFIRMED
        }
        return pause(context, current)
    }

    private fun pause(
        context: Context,
        target: SensitiveAppIdentity,
    ): SensitivePauseResult = synchronized(transitionLock) {
        val appContext = context.applicationContext
        if (!hasControlPermission(appContext)) return@synchronized SensitivePauseResult.MISSING_PRIVILEGE
        if (!SensitiveModeNotifier.canNotify(appContext)) {
            return@synchronized SensitivePauseResult.MISSING_NOTIFICATION_PERMISSION
        }
        val component = accessibilityComponent(appContext)
        val existing = currentSession(appContext)
        if (existing?.phase == SensitiveLaunchPhase.PREPARING) {
            restoreLocked(appContext, existing.id)
            return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
        }
        if (existing != null) {
            if (existing.phase == SensitiveLaunchPhase.RECOVERING) {
                return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
            }
            if (!AccessibilityServiceListEditor.contains(enabledServices(appContext), component)) {
                if (!SensitiveModeNotifier.showPaused(appContext, existing)) {
                    return@synchronized SensitivePauseResult.MISSING_NOTIFICATION_PERMISSION
                }
                return@synchronized SensitivePauseResult.ALREADY_PAUSED
            }
            // 活动会话与系统启用状态冲突时先恢复闭环，绝不覆盖成一个新会话。
            restoreLocked(appContext, existing.id)
            return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
        }

        // 写系统设置前再读一次完整列表。若本组件本来就没启用，不创建一个会擅自重启它的会话。
        val beforeWrite = enabledServices(appContext)
        if (!AccessibilityServiceListEditor.contains(beforeWrite, component)) {
            return@synchronized SensitivePauseResult.MONITOR_ALREADY_DISABLED
        }
        val session = SensitiveSession(
            id = UUID.randomUUID().toString(),
            targetPackage = target.packageName,
            targetLabel = target.label,
            targetSigningDigest = target.signingDigest,
            targetVersionCode = target.versionCode,
            targetUserSerial = target.userSerial,
            startedAtMs = System.currentTimeMillis(),
            phase = SensitiveLaunchPhase.PREPARING,
            restoreAttemptId = null,
            targetEntered = false,
            exitPrompted = false,
        )

        val durable = prefs(appContext).edit()
            .putBoolean(KEY_ACTIVE, true)
            .putString(KEY_TARGET_PACKAGE, target.packageName)
            .putString(KEY_TARGET_LABEL, target.label)
            .putString(KEY_TARGET_SIGNING_DIGEST, target.signingDigest)
            .putLong(KEY_TARGET_VERSION_CODE, target.versionCode)
            .putLong(KEY_TARGET_USER_SERIAL, target.userSerial)
            .putBoolean(KEY_TARGET_ENTERED, false)
            .putBoolean(KEY_EXIT_PROMPTED, false)
            .putLong(KEY_STARTED_AT, session.startedAtMs)
            .putString(KEY_SESSION_ID, session.id)
            .putString(KEY_PHASE, session.phase.name)
            .commit()
        if (!durable || !scheduleWork(
                appContext,
                session.id,
                MAX_PAUSE_MINUTES,
                TimeUnit.MINUTES,
                WORK_KIND_DEADLINE,
            )
        ) {
            clearSession(appContext, session.id, cancelScheduledWork = true)
            return@synchronized SensitivePauseResult.RESTORE_NOT_SCHEDULED
        }

        // 排队兜底后重新读取，避免把等待期间用户新增/删除的其他服务写回旧快照。
        val freshBeforeWrite = enabledServices(appContext)
        if (!AccessibilityServiceListEditor.contains(freshBeforeWrite, component)) {
            clearSession(appContext, session.id, cancelScheduledWork = true)
            return@synchronized SensitivePauseResult.MONITOR_ALREADY_DISABLED
        }
        val expectedOthers = AccessibilityServiceListEditor.without(freshBeforeWrite, component)
        val updated = AccessibilityServiceListEditor.remove(freshBeforeWrite, component)
        val written = Settings.Secure.putString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated,
        )
        val readBack = enabledServices(appContext)
        val settingsVerified = written &&
            !AccessibilityServiceListEditor.contains(readBack, component) &&
            AccessibilityServiceListEditor.without(readBack, component) == expectedOthers
        if (!settingsVerified) {
            restoreLocked(appContext, session.id)
            return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
        }

        // 不依赖 Service.onDestroy：系统写入一旦确认，就同步持久化覆盖缺口再允许启动银行。
        val gapPersisted = runCatching {
            LedgerKernel.markA11yPaused(
                "${target.label} 安全模式：用户授权临时暂停行为监视",
            )
        }.isSuccess
        if (!gapPersisted) {
            restoreLocked(appContext, session.id)
            return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
        }
        // 通知必须晚于“移除 + 读回 + 缺口落盘”，避免恢复动作抢跑暂停流程。
        if (!SensitiveModeNotifier.showPaused(appContext, session)) {
            restoreLocked(appContext, session.id)
            return@synchronized SensitivePauseResult.MISSING_NOTIFICATION_PERMISSION
        }
        // 到这里才满足安全启动的全部前置条件；进程重建绝不能把 PREPARING 当成已暂停。
        if (!prefs(appContext).edit()
                .putString(KEY_PHASE, SensitiveLaunchPhase.PAUSED.name)
                .commit()
        ) {
            restoreLocked(appContext, session.id)
            return@synchronized SensitivePauseResult.SETTINGS_WRITE_FAILED
        }
        SensitivePauseResult.PAUSED
    }

    fun restore(
        context: Context,
        expectedSessionId: String? = null,
    ): Boolean = synchronized(transitionLock) {
        restoreLocked(context.applicationContext, expectedSessionId)
    }

    private fun restoreLocked(
        appContext: Context,
        expectedSessionId: String?,
    ): Boolean {
        val session = currentSession(appContext) ?: return true
        // 旧 Worker/旧通知永远不能结束新会话。
        if (expectedSessionId != null && session.id != expectedSessionId) return true
        if (!hasControlPermission(appContext)) return false
        SensitiveSessionMonitorService.stop(appContext)
        val component = accessibilityComponent(appContext)
        var beforeWrite = enabledServices(appContext)
        if (session.phase == SensitiveLaunchPhase.PREPARING) {
            // 进程可能在移除组件前或后死亡；统一记录不确定区间并强制走一次真实重连。
            runCatching {
                LedgerKernel.markA11yPaused("敏感应用安全模式准备中断：恢复前覆盖状态不确定")
            }.getOrElse {
                SensitiveModeNotifier.showRestoreFailed(appContext)
                return false
            }
        }
        val restoreAttemptId = UUID.randomUUID().toString()
        if (!prefs(appContext).edit()
                .putString(KEY_PHASE, SensitiveLaunchPhase.RECOVERING.name)
                .putString(KEY_RESTORE_ATTEMPT_ID, restoreAttemptId)
                .commit()
        ) {
            SensitiveModeNotifier.showRestoreFailed(appContext)
            return false
        }
        // 若设置中已经存在本组件，也必须先移除再加回；否则“已启用但未绑定”不会产生
        // 新 onServiceConnected，RECOVERING 会永远无法得到真实确认。
        if (AccessibilityServiceListEditor.contains(beforeWrite, component)) {
            val retryOthers = AccessibilityServiceListEditor.without(beforeWrite, component)
            val removed = Settings.Secure.putString(
                appContext.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                AccessibilityServiceListEditor.remove(beforeWrite, component),
            )
            val removedReadBack = enabledServices(appContext)
            if (!removed || AccessibilityServiceListEditor.contains(removedReadBack, component) ||
                AccessibilityServiceListEditor.without(removedReadBack, component) != retryOthers
            ) {
                SensitiveModeNotifier.showRestoreFailed(appContext)
                return false
            }
            beforeWrite = removedReadBack
        }
        val expectedOthers = AccessibilityServiceListEditor.without(beforeWrite, component)
        val updated = AccessibilityServiceListEditor.add(beforeWrite, component)
        val written = Settings.Secure.putString(
            appContext.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            updated,
        )
        val readBack = enabledServices(appContext)
        val restored = written &&
            AccessibilityServiceListEditor.contains(readBack, component) &&
            AccessibilityServiceListEditor.without(readBack, component) == expectedOthers
        if (restored) {
            // 这里只能说明设置已写回；必须保留会话、通知与缺口，等待真实连接回调确认。
            SensitiveModeNotifier.showRecovering(appContext, session)
            if (!scheduleWork(
                    appContext,
                    session.id,
                    15L,
                    TimeUnit.SECONDS,
                    WORK_KIND_RECOVERY_CHECK,
                    restoreAttemptId,
                )
            ) {
                SensitiveModeNotifier.showRestoreFailed(appContext)
            }
            return true
        } else {
            SensitiveModeNotifier.showRestoreFailed(appContext)
        }
        return restored
    }

    /**
     * 只有本次 remove/add 产生的 Service 连接回调，且系统列表仍确认组件已启用，
     * 才结束恢复会话。旧实例、旧恢复尝试和 add 失败后的迟到回调都必须拒绝。
     */
    internal fun confirmServiceConnected(
        context: Context,
        observedAttemptId: String?,
        onConnectionAccepted: () -> Boolean,
    ): SensitiveConnectionConfirmation =
        synchronized(transitionLock) {
            val appContext = context.applicationContext
            val session = currentSession(appContext)
            if (session == null) {
                if (observedAttemptId != null) {
                    return@synchronized SensitiveConnectionConfirmation.REJECTED
                }
                return@synchronized if (runCatching(onConnectionAccepted).getOrDefault(false)) {
                    SensitiveConnectionConfirmation.CONNECTED
                } else {
                    SensitiveConnectionConfirmation.REJECTED
                }
            }
            val ownServiceEnabled = AccessibilityServiceListEditor.contains(
                enabledServices(appContext),
                accessibilityComponent(appContext),
            )
            if (!SensitiveRecoveryRules.mayConfirm(
                    phase = session.phase,
                    currentAttemptId = session.restoreAttemptId,
                    observedAttemptId = observedAttemptId,
                    ownServiceEnabled = ownServiceEnabled,
                )
            ) {
                return@synchronized SensitiveConnectionConfirmation.REJECTED
            }
            // owner 接管与新鲜心跳必须先于会话清除；否则外界可能看到“恢复完成”但服务
            // 尚未真正接管，旧实例的迟到 onDestroy 也可能在这个窗口清掉心跳。
            if (!runCatching(onConnectionAccepted).getOrDefault(false)) {
                SensitiveModeNotifier.showRestoreFailed(appContext)
                return@synchronized SensitiveConnectionConfirmation.REJECTED
            }
            if (!clearSession(appContext, session.id, cancelScheduledWork = true)) {
                // 连接和耐久心跳已经成立；会话清理失败只保留恢复告警，不能让 Service
                // 回滚为“不采集”，否则 UI/心跳显示在线但所有事件会被静默丢弃。
                SensitiveModeNotifier.showRestoreFailed(appContext, session)
                return@synchronized SensitiveConnectionConfirmation.CONNECTED_SESSION_PENDING
            }
            SensitiveModeNotifier.cancel(appContext, session)
            SensitiveConnectionConfirmation.CONNECTED
        }

    internal fun currentRestoreAttemptId(context: Context): String? =
        currentSession(context)?.takeIf { it.phase == SensitiveLaunchPhase.RECOVERING }
            ?.restoreAttemptId

    fun restoreIfExpired(context: Context) {
        val session = currentSession(context) ?: return
        if (session.phase == SensitiveLaunchPhase.PREPARING) {
            thread(name = "sensitive-mode-preparing-recovery") {
                restore(context, session.id)
            }
            return
        }
        if (session.phase == SensitiveLaunchPhase.RECOVERING) {
            SensitiveModeNotifier.showRestoreFailed(context)
            return
        }
        if (session.phase == SensitiveLaunchPhase.LAUNCHED) {
            // 仅尝试恢复人工提示监视器；后台启动受系统限制时安全失败，绝不据此自动恢复。
            SensitiveSessionMonitorService.start(context, session.id)
        }
        if (session.startedAtMs <= 0L || System.currentTimeMillis() - session.startedAtMs >=
            TimeUnit.MINUTES.toMillis(MAX_PAUSE_MINUTES)
        ) {
            // 无法可靠确认敏感应用流程是否结束，因此只提醒，不盲目重启无障碍。
            SensitiveModeNotifier.showRestoreDue(context, session)
        }
    }

    internal fun markTargetLaunched(context: Context, sessionId: String): Boolean =
        synchronized(transitionLock) {
            val session = currentSession(context) ?: return@synchronized false
            if (session.id != sessionId) return@synchronized false
            if (!sessionTargetIdentityStillValid(context, session)) return@synchronized false
            val ownServiceAbsent = !AccessibilityServiceListEditor.contains(
                enabledServices(context.applicationContext),
                accessibilityComponent(context.applicationContext),
            )
            if (!SensitivePhaseRules.mayLaunchTarget(session.phase, ownServiceAbsent)) {
                return@synchronized false
            }
            prefs(context).edit().putString(KEY_PHASE, SensitiveLaunchPhase.LAUNCHED.name).commit()
        }

    internal fun sessionTargetIdentityStillValid(context: Context, session: SensitiveSession): Boolean {
        val current = PackageIdentityResolver.resolve(context, session.targetPackage) ?: return false
        return current.signingDigest == session.targetSigningDigest &&
            current.userSerial == session.targetUserSerial &&
            context.packageManager.getLaunchIntentForPackage(session.targetPackage) != null
    }

    internal fun markTargetEntered(context: Context, sessionId: String): Boolean =
        synchronized(transitionLock) {
            val session = currentSession(context) ?: return@synchronized false
            if (session.id != sessionId || session.phase != SensitiveLaunchPhase.LAUNCHED) {
                return@synchronized false
            }
            prefs(context).edit()
                .putBoolean(KEY_TARGET_ENTERED, true)
                .putBoolean(KEY_EXIT_PROMPTED, false)
                .commit()
        }

    internal fun markExitCandidate(context: Context, sessionId: String): SensitiveSession? =
        synchronized(transitionLock) {
            val session = currentSession(context) ?: return@synchronized null
            if (session.id != sessionId || session.phase != SensitiveLaunchPhase.LAUNCHED ||
                !session.targetEntered || session.exitPrompted
            ) {
                return@synchronized null
            }
            if (!prefs(context).edit().putBoolean(KEY_EXIT_PROMPTED, true).commit()) {
                return@synchronized null
            }
            currentSession(context)
        }

    internal fun currentSession(context: Context): SensitiveSession? {
        val values = prefs(context)
        if (!values.getBoolean(KEY_ACTIVE, false)) return null
        val id = values.getString(KEY_SESSION_ID, null)?.takeIf(String::isNotBlank) ?: return null
        val targetPackage = values.getString(KEY_TARGET_PACKAGE, null)?.takeIf(String::isNotBlank) ?: return null
        val targetLabel = values.getString(KEY_TARGET_LABEL, null)?.takeIf(String::isNotBlank) ?: return null
        val phase = runCatching {
            SensitiveLaunchPhase.valueOf(values.getString(KEY_PHASE, null).orEmpty())
        }.getOrNull() ?: return null
        return SensitiveSession(
            id = id,
            targetPackage = targetPackage,
            targetLabel = targetLabel,
            targetSigningDigest = values.getString(KEY_TARGET_SIGNING_DIGEST, "").orEmpty(),
            targetVersionCode = values.getLong(KEY_TARGET_VERSION_CODE, 0L),
            targetUserSerial = values.getLong(KEY_TARGET_USER_SERIAL, -1L),
            startedAtMs = values.getLong(KEY_STARTED_AT, 0L),
            phase = phase,
            restoreAttemptId = values.getString(KEY_RESTORE_ATTEMPT_ID, null),
            targetEntered = values.getBoolean(KEY_TARGET_ENTERED, false),
            exitPrompted = values.getBoolean(KEY_EXIT_PROMPTED, false),
        )
    }

    internal fun handleRestoreDeadline(context: Context, sessionId: String) {
        val session = synchronized(transitionLock) {
            currentSession(context)?.takeIf {
                it.id == sessionId && it.phase != SensitiveLaunchPhase.RECOVERING
            }
        } ?: return
        if (session.phase == SensitiveLaunchPhase.PREPARING) {
            restore(context, session.id)
            return
        }
        SensitiveModeNotifier.showRestoreDue(context, session)
    }

    internal fun handleRecoveryCheck(context: Context, sessionId: String, attemptId: String?) {
        val recoveryStillPending = synchronized(transitionLock) {
            currentSession(context)?.takeIf {
                it.id == sessionId &&
                    it.phase == SensitiveLaunchPhase.RECOVERING &&
                    it.restoreAttemptId == attemptId
            } != null
        }
        if (!recoveryStillPending) return
        SensitiveModeNotifier.showRestoreFailed(context)
    }

    fun activeDisconnectNote(context: Context): String? {
        if (!isActive(context)) return null
        val label = prefs(context).getString(KEY_TARGET_LABEL, null) ?: "敏感应用"
        return "$label 安全模式：用户授权临时暂停行为监视"
    }

    internal fun notifyUnsafeLaunch(context: Context, profile: SensitiveAppProfile) {
        SensitiveModeNotifier.showUnsafeLaunch(context, profile)
    }

    private fun scheduleWork(
        context: Context,
        sessionId: String,
        delay: Long,
        unit: TimeUnit,
        kind: String,
        attemptId: String? = null,
    ): Boolean = runCatching {
        val request = OneTimeWorkRequestBuilder<SensitiveModeRestoreWorker>()
            .setInitialDelay(delay, unit)
            .setInputData(
                workDataOf(
                    WORK_SESSION_ID to sessionId,
                    WORK_KIND to kind,
                    WORK_ATTEMPT_ID to attemptId,
                )
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_RESTORE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        ).result.get(5, TimeUnit.SECONDS)
        true
    }.getOrDefault(false)

    private fun enabledServices(context: Context): String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    )

    private fun accessibilityComponent(context: Context): String = ComponentName(
        context,
        PaymentBehaviorAccessibilityService::class.java,
    ).flattenToString()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun clearSession(context: Context, sessionId: String, cancelScheduledWork: Boolean): Boolean {
        if (currentSession(context)?.id != sessionId) return true
        if (!prefs(context).edit().clear().commit()) return false
        if (cancelScheduledWork) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_RESTORE_WORK)
        }
        return true
    }
}

/** 保持原有顺序的纯函数列表编辑器，确保不覆盖其他无障碍服务。 */
internal object AccessibilityServiceListEditor {
    fun contains(raw: String?, component: String): Boolean {
        val expected = canonicalIdentity(component)
        return parse(raw).any { canonicalIdentity(it) == expected }
    }

    fun remove(raw: String?, component: String): String {
        val expected = canonicalIdentity(component)
        return parse(raw)
        .filterNot { canonicalIdentity(it) == expected }
        .joinToString(":")
    }

    fun add(raw: String?, component: String): String = parse(raw)
        .toMutableList()
        .apply {
            val expected = canonicalIdentity(component)
            if (none { canonicalIdentity(it) == expected }) add(component)
        }
        .joinToString(":")

    /** 用规范身份比较读回结果；原始字符串的缩写/完整写法变化不代表其他服务被修改。 */
    fun without(raw: String?, component: String): List<String> {
        val expected = canonicalIdentity(component)
        return parse(raw)
            .map(::canonicalIdentity)
            .filterNot { it == expected }
    }

    private fun parse(raw: String?): List<String> = raw.orEmpty()
        .split(':')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(::canonicalIdentity)

    private fun canonicalIdentity(component: String): String {
        val separator = component.indexOf('/')
        if (separator <= 0 || separator == component.lastIndex) return component
        val packageName = component.substring(0, separator)
        val className = component.substring(separator + 1)
        val expandedClass = if (className.startsWith('.')) "$packageName$className" else className
        return "$packageName/$expandedClass"
    }
}

class SensitiveAppLaunchActivity : Activity() {
    private var targetLaunchIssued = false
    private var targetWasEntered = false
    private var restoreStarted = false
    private var sessionId: String? = null
    private var targetPackage: String? = null
    private var targetLabel: String = "敏感应用"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestedPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE)
        val existing = SensitiveAppMode.currentSession(this)
        val resolvedPackage = existing?.targetPackage ?: requestedPackage
        if (resolvedPackage.isNullOrBlank()) {
            Toast.makeText(this, "未指定敏感应用", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (existing != null && requestedPackage != null && existing.targetPackage != requestedPackage) {
            Toast.makeText(this, "已有其他敏感应用会话，未启动新应用", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        targetPackage = resolvedPackage
        targetLabel = existing?.targetLabel
            ?: SensitiveAppRegistry.activeProfile(this, resolvedPackage)?.identity?.label
            ?: resolvedPackage
        if (!SensitiveAppMode.isTargetInstalled(this, resolvedPackage)) {
            Toast.makeText(this, "未找到 $targetLabel", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        when (existing?.phase) {
            SensitiveLaunchPhase.PREPARING -> {
                thread(name = "sensitive-mode-preparing-recovery") {
                    SensitiveAppMode.restore(this, existing.id)
                    finishWithToast("安全准备曾被中断，正在恢复行为监视；未启动 $targetLabel")
                }
            }
            SensitiveLaunchPhase.LAUNCHED -> {
                // 进程重建后仍只认同一持久 session；多窗口/PiP 不自动恢复。
                sessionId = existing.id
                // 只有旧进程已耐久确认目标进入，重建后的代理才可等待返回恢复。
                // 否则一次系统弹窗或旋转引起的 onPause 不能凭空升级为“目标已进入”。
                targetLaunchIssued = existing.targetEntered
                targetWasEntered = existing.targetEntered
            }
            SensitiveLaunchPhase.PAUSED -> prepareExistingSession(existing)
            SensitiveLaunchPhase.RECOVERING -> {
                Toast.makeText(this, "行为监视仍在恢复，请稍后重试", Toast.LENGTH_LONG).show()
                finish()
            }
            null -> prepareNewSession()
        }
    }

    private fun prepareExistingSession(session: SensitiveSession) {
        thread(name = "sensitive-mode-launch") {
            if (!SensitiveAppMode.markTargetLaunched(this, session.id)) {
                finishWithToast("安全会话已变化，未启动 $targetLabel")
                return@thread
            }
            sessionId = session.id
            SensitiveSessionMonitorService.start(this, session.id)
            launchTargetOnMain()
        }
    }

    private fun prepareNewSession() {
        val requested = targetPackage ?: return
        thread(name = "sensitive-mode-pause") {
            when (SensitiveAppMode.pauseForPackage(this, requested)) {
                SensitivePauseResult.PAUSED,
                SensitivePauseResult.ALREADY_PAUSED -> {
                    val session = SensitiveAppMode.currentSession(this)
                    if (session == null || session.phase != SensitiveLaunchPhase.PAUSED ||
                        session.targetPackage != requested ||
                        !SensitiveAppMode.markTargetLaunched(this, session.id)
                    ) {
                        finishWithToast("安全模式状态不可用，未启动 $targetLabel")
                        return@thread
                    }
                    sessionId = session.id
                    SensitiveSessionMonitorService.start(this, session.id)
                    launchTargetOnMain()
                }
                SensitivePauseResult.MONITOR_ALREADY_DISABLED -> {
                    // 用户原本就关闭了本服务；启动后不创建恢复会话，也绝不擅自开启服务。
                    runOnUiThread {
                        launchTargetOnMainThread(requireSession = false)
                        finish()
                    }
                }
                SensitivePauseResult.TARGET_NOT_AVAILABLE -> finishWithToast("目标应用已卸载或不可启动")
                SensitivePauseResult.TARGET_NOT_CONFIRMED -> finishWithToast("应用身份已变化，请重新确认敏感应用")
                SensitivePauseResult.MISSING_PRIVILEGE -> finishWithToast("缺少 ADB 安全模式授权")
                SensitivePauseResult.MISSING_NOTIFICATION_PERMISSION ->
                    finishWithToast("请先开启本 App 通知及“敏感应用安全模式”通知渠道")
                SensitivePauseResult.RESTORE_NOT_SCHEDULED,
                SensitivePauseResult.SETTINGS_WRITE_FAILED ->
                    finishWithToast("无法安全暂停，未启动 $targetLabel")
            }
        }
    }

    private fun launchTargetOnMain() {
        runOnUiThread { launchTargetOnMainThread() }
    }

    private fun launchTargetOnMainThread(requireSession: Boolean = true) {
        if (isFinishing || isDestroyed) return
        val requested = targetPackage ?: return
        val currentSession = SensitiveAppMode.currentSession(this)
        val identityValid = if (requireSession) {
            currentSession != null && currentSession.id == sessionId &&
                SensitiveAppMode.sessionTargetIdentityStillValid(this, currentSession)
        } else {
            SensitiveAppRegistry.activeProfile(this, requested) != null
        }
        if (!identityValid) {
            val expected = sessionId
            thread(name = "sensitive-mode-identity-failure") {
                if (expected != null) SensitiveAppMode.restore(this, expected)
                finishWithToast("$targetLabel 身份已变化，未启动并正在恢复监视")
            }
            return
        }
        val launchIntent = packageManager.getLaunchIntentForPackage(requested)
        targetLaunchIssued = true
        if (launchIntent == null || runCatching { startActivity(launchIntent) }.isFailure) {
            targetLaunchIssued = false
            val expected = sessionId
            thread(name = "sensitive-mode-launch-failure") {
                if (expected != null) SensitiveAppMode.restore(this, expected)
                finishWithToast("$targetLabel 启动失败，正在恢复监视")
            }
        }
    }

    override fun onPause() {
        if (targetLaunchIssued) {
            targetWasEntered = true
            sessionId?.let { SensitiveAppMode.markTargetEntered(this, it) }
        }
        super.onPause()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!targetWasEntered || restoreStarted) return
        val expected = sessionId ?: return
        val targetMayUsePip = targetPackage?.let {
            PackageIdentityResolver.launchActivityMayUsePictureInPicture(this, it)
        } == true
        if (isInMultiWindowMode || isInPictureInPictureMode || targetMayUsePip) {
            SensitiveAppMode.markExitCandidate(this, expected)?.let {
                SensitiveModeNotifier.showExitCandidate(this, it)
            }
            return
        }
        restoreStarted = true
        thread(name = "sensitive-mode-return") {
            val accepted = SensitiveAppMode.restore(this, expected)
            val confirmed = accepted && !SensitiveAppMode.isActive(this)
            finishWithToast(
                when {
                    confirmed -> "已恢复自动账本行为监视"
                    accepted -> "正在等待系统确认行为监视已恢复"
                    else -> "恢复失败，请点击安全模式通知重试"
                }
            )
        }
    }

    private fun finishWithToast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    companion object {
        private const val EXTRA_TARGET_PACKAGE = "target_package"

        fun intent(context: Context, packageName: String): Intent =
            Intent(context, SensitiveAppLaunchActivity::class.java)
                .putExtra(EXTRA_TARGET_PACKAGE, packageName)
    }
}

class SensitiveModeRestoreWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val sessionId = inputData.getString(SensitiveAppMode.WORK_SESSION_ID)
            ?: return Result.failure()
        when (inputData.getString(SensitiveAppMode.WORK_KIND)) {
            SensitiveAppMode.WORK_KIND_DEADLINE -> {
                // 不能确认银行是否仍在前台，截止时间只升级提醒，绝不盲目恢复。
                SensitiveAppMode.handleRestoreDeadline(applicationContext, sessionId)
            }
            SensitiveAppMode.WORK_KIND_RECOVERY_CHECK -> {
                SensitiveAppMode.handleRecoveryCheck(
                    applicationContext,
                    sessionId,
                    inputData.getString(SensitiveAppMode.WORK_ATTEMPT_ID),
                )
            }
            else -> return Result.failure()
        }
        return Result.success()
    }
}

class SensitiveModeRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SensitiveModeNotifier.ACTION_RESTORE) return
        val sessionId = intent.getStringExtra(SensitiveAppMode.RESTORE_SESSION_ID) ?: return
        val pending = goAsync()
        thread(name = "sensitive-mode-restore") {
            try {
                SensitiveAppMode.restore(context, sessionId)
            } finally {
                pending.finish()
            }
        }
    }
}

internal object SensitiveModeNotifier {
    const val ACTION_RESTORE = "com.hulk.pillsapp.action.RESTORE_SENSITIVE_MODE"
    private const val CHANNEL_ID = "sensitive_app_mode"
    const val FOREGROUND_NOTIFICATION_ID = 19_200
    private const val UNSAFE_NOTIFICATION_ID = 19_201
    private const val SUGGESTION_NOTIFICATION_ID = 19_202
    const val ORPHAN_FOREGROUND_NOTIFICATION_ID = 19_203
    private const val SESSION_NOTIFICATION_ID = 19_204

    @SuppressLint("MissingPermission")
    fun showPaused(context: Context, session: SensitiveSession): Boolean {
        if (!canNotify(context)) return false
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${session.targetLabel} 安全模式已开启")
            .setContentText("行为监视已暂停；返回后恢复，超时只提醒确认")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
        return postAndVerify(context, sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
    }

    @SuppressLint("MissingPermission")
    fun showUnsafeLaunch(context: Context, profile: SensitiveAppProfile) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val safeLaunch = PendingIntent.getActivity(
            context,
            profile.identity.packageName.hashCode(),
            SensitiveAppLaunchActivity.intent(context, profile.identity.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(android.net.Uri.parse("pillsapp://safe/${profile.identity.packageName}")),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${profile.identity.label} 可能排斥行为监视")
            .setContentText("点击安全重开：先暂停行为监视，再进入应用")
            .setContentIntent(safeLaunch)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(UNSAFE_NOTIFICATION_ID, notification) }
    }

    @SuppressLint("MissingPermission")
    fun showSuggestion(context: Context, suggestion: SensitiveAppSuggestion) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            5,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("发现可能需要安全模式的应用")
            .setContentText("${suggestion.identity.label} 出现屏幕监视风险提示，点击确认是否学习")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(SUGGESTION_NOTIFICATION_ID, notification)
        }
    }

    fun cancelSuggestion(context: Context) {
        NotificationManagerCompat.from(context).cancel(SUGGESTION_NOTIFICATION_ID)
    }

    fun monitoringNotification(context: Context, session: SensitiveSession): android.app.Notification {
        ensureChannel(context)
        return sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("${session.targetLabel} 安全模式监视中")
            .setContentText("只判断应用前后台；不会录屏，离开后请确认恢复")
            .setOnlyAlertOnce(true)
            .build()
    }

    fun orphanedMonitorNotification(context: Context): android.app.Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("敏感应用会话已结束")
            .setContentText("正在关闭临时前后台判断")
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    @SuppressLint("MissingPermission")
    fun showExitCandidate(context: Context, session: SensitiveSession) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("可能已离开 ${session.targetLabel}")
            .setContentText("若银行流程已结束，请点击恢复；取验证码等临时跳转不要恢复")
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showUsageAccessUnavailable(context: Context, session: SensitiveSession) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("${session.targetLabel} 安全模式仍在暂停")
            .setContentText("无法读取前后台状态；离开后请点此通知恢复")
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showRestoreDue(context: Context, session: SensitiveSession) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("行为监视仍处于暂停")
            .setContentText("若已离开${session.targetLabel}，请点击恢复；使用中不会自动重启")
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showRecovering(context: Context, session: SensitiveSession) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("正在恢复行为监视")
            .setContentText("等待系统确认服务已连接；若长时间未完成可点此重试")
            .setOnlyAlertOnce(true)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
        }
    }

    @SuppressLint("MissingPermission")
    fun showRestoreFailed(context: Context) {
        val session = SensitiveAppMode.currentSession(context) ?: return
        showRestoreFailed(context, session)
    }

    @SuppressLint("MissingPermission")
    fun showRestoreFailed(context: Context, session: SensitiveSession) {
        if (!canNotify(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            3,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = sessionNotificationBuilder(context, session)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("行为监视恢复失败")
            .setContentText("点击重试；若仍失败，请到无障碍设置重新开启")
            .setContentIntent(open)
            .build()
        runCatching {
            NotificationManagerCompat.from(context)
                .notify(sessionTag(session.id), SESSION_NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context, session: SensitiveSession) {
        NotificationManagerCompat.from(context).cancel(FOREGROUND_NOTIFICATION_ID)
        cancelSessionNotice(context, session.id)
        NotificationManagerCompat.from(context).cancel(UNSAFE_NOTIFICATION_ID)
    }

    fun cancelSessionNotice(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context)
            .cancel(sessionTag(sessionId), SESSION_NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "敏感应用安全模式",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "银行应用期间暂停与恢复行为监视"
            }
        )
    }

    fun canNotify(context: Context): Boolean = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return@runCatching false
        ensureChannel(context)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return@runCatching false
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(CHANNEL_ID)
        channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    private fun postAndVerify(
        context: Context,
        tag: String,
        id: Int,
        notification: android.app.Notification,
    ): Boolean =
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.notify(tag, id, notification)
            // NotificationManager 入队是异步的；小米在系统繁忙时立即读回会短暂为空。
            // 最多等待 1 秒确认通知真正可见，仍保持“可撤销通知成立后才允许暂停”的门槛。
            repeat(40) {
                if (manager.activeNotifications.any { it.id == id && it.tag == tag }) {
                    return@runCatching true
                }
                android.os.SystemClock.sleep(25L)
            }
            false
        }.getOrDefault(false)

    private fun sessionTag(sessionId: String): String = "sensitive-session:$sessionId"

    private fun restoreIntent(context: Context, sessionId: String): Intent =
        Intent(context, SensitiveModeRestoreReceiver::class.java)
            .setAction(ACTION_RESTORE)
            .setData(android.net.Uri.parse("pillsapp://restore/$sessionId"))
            .putExtra(SensitiveAppMode.RESTORE_SESSION_ID, sessionId)

    private fun sessionNotificationBuilder(
        context: Context,
        session: SensitiveSession,
    ): NotificationCompat.Builder {
        val restore = PendingIntent.getBroadcast(
            context,
            session.id.hashCode(),
            restoreIntent(context, session.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .addAction(0, "已离开，立即恢复", restore)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }
}
