package com.hulk.pillsapp.ledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hulk.pillsapp.NotificationListenerState
import com.hulk.pillsapp.BehaviorAccessibilityState
import kotlin.concurrent.thread

/** 开机自检（V1.1 §6.2）：通知使用权失效即开缺口，并重新登记周期健康检查。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        thread(name = "ledger-boot-check") {
            try {
                BootHealthAudit.run(
                    notificationHealthy = runCatching {
                        NotificationListenerState.isNotificationListenerEnabled(appContext)
                    }.getOrDefault(false),
                    behaviorHealthy = runCatching {
                        BehaviorAccessibilityState.isEnabled(appContext) &&
                            BehaviorAccessibilityState.isServiceConnected() &&
                            LedgerKernel.isA11yHeartbeatFresh()
                    }.getOrDefault(false),
                    openGap = LedgerKernel::openGapAsync,
                    enqueueHealthCheck = { HealthCheckWorker.enqueue(appContext) },
                )
            } finally {
                pendingResult.finish()
            }
        }
    }
}

/**
 * 开机广播不得因数据库队列繁忙或 WorkManager 短暂不可用而崩溃进程。
 * 缺口写入为幂等异步任务；单项失败不得阻断其他审计项或周期健康检查。
 */
internal object BootHealthAudit {
    fun run(
        notificationHealthy: Boolean,
        behaviorHealthy: Boolean,
        openGap: (detector: String, note: String?) -> Unit,
        enqueueHealthCheck: () -> Unit,
    ) {
        if (!notificationHealthy) {
            runCatching {
                openGap(GapDetectors.BOOT_CHECK, "开机校验：通知使用权已失效")
            }
        }
        if (!behaviorHealthy) {
            runCatching {
                openGap(GapDetectors.A11Y_SERVICE, "开机校验：行为学习服务尚未连接或权限已失效")
            }
        }
        runCatching(enqueueHealthCheck)
    }
}
