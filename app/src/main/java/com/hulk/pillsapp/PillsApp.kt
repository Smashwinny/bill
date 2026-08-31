package com.hulk.pillsapp

import android.app.Application
import android.content.ComponentName
import com.hulk.pillsapp.ledger.HealthCheckWorker
import com.hulk.pillsapp.ledger.LedgerKernel
import com.hulk.pillsapp.ledger.ManualLedgerMigrationRepository

class PillsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedgerKernel.init(this)
        // 在通知/短信历史恢复队列启动前安排同设备手工账本同步，避免迁移任务被大批恢复工作饿死。
        ManualLedgerMigrationRepository.syncFromInstalledManualLedger(this)
        // 异常退出/进程重建时，到期会话只提醒确认；银行仍在前台时不能盲目恢复。
        SensitiveAppMode.restoreIfExpired(this)
        // 通知/SMS 回调已 fsync 但尚未来得及入库的加密待办在启动时幂等重放。
        LedgerKernel.drainObservationOutbox()
        // M5：无障碍回调已 fsync 但尚未来得及入库的脱敏片段在启动时幂等重放。
        LedgerKernel.drainBehaviorOutbox()
        LedgerKernel.runLegacyMigrationIfNeeded(this)
        // 进程重启兜底：上次被杀时滞留的 PENDING_PARSE 在此续跑（V1.1 §9 用例 14）。
        LedgerKernel.drainPendingParse()
        // 短信权限已授予时做差量回填（V1.1 §3.2）。
        LedgerKernel.backfillSms(this)
        // M3：历史观察/修订按解析器版本幂等回扫；新观察仍走实时增量发现。
        LedgerKernel.runDebtDiscovery()
        HealthCheckWorker.enqueue(this)
        runDebugSelfTests(this)
        if (NotificationListenerState.isNotificationListenerEnabled(this)) {
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, NotificationListenerService::class.java)
            )
        }
    }
}
