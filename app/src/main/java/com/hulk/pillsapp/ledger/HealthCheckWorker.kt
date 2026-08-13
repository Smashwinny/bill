package com.hulk.pillsapp.ledger

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hulk.pillsapp.NotificationListenerState
import com.hulk.pillsapp.BehaviorAccessibilityState
import java.util.concurrent.TimeUnit

/**
 * 周期健康检查（V1.1 §6.3）：校验通知使用权是否仍然有效。
 * 失效即开缺口；恢复只关闭结束时间。HyperOS 保活依赖用户按 V1.1 §2 完成设置。
 */
class HealthCheckWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val enabled = NotificationListenerState.isNotificationListenerEnabled(applicationContext)
        if (enabled) {
            LedgerKernel.closeOpenGaps(GapDetectors.HEALTH_CHECK)
        } else {
            LedgerKernel.openGap(GapDetectors.HEALTH_CHECK, "周期健康检查：通知使用权已失效")
        }
        if (BehaviorAccessibilityState.isEnabled(applicationContext) &&
            LedgerKernel.isA11yHeartbeatFresh()
        ) {
            LedgerKernel.closeOpenGaps(GapDetectors.A11Y_SERVICE)
        } else {
            LedgerKernel.openGap(GapDetectors.A11Y_SERVICE, "周期健康检查：行为学习服务未连接或权限已失效")
        }
        LedgerKernel.markHealthCheck()
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "ledger_health_check"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<HealthCheckWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
