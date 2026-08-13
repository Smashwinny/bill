package com.hulk.pillsapp

import android.app.Application
import android.content.ComponentName
import com.hulk.pillsapp.ledger.HealthCheckWorker
import com.hulk.pillsapp.ledger.LedgerKernel

class PillsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedgerKernel.init(this)
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
