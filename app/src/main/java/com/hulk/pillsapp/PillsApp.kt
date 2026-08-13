package com.hulk.pillsapp

import android.app.Application
import com.hulk.pillsapp.ledger.HealthCheckWorker
import com.hulk.pillsapp.ledger.LedgerKernel

class PillsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LedgerKernel.init(this)
        LedgerKernel.runLegacyMigrationIfNeeded(this)
        // 进程重启兜底：上次被杀时滞留的 PENDING_PARSE 在此续跑（V1.1 §9 用例 14）。
        LedgerKernel.drainPendingParse()
        // 短信权限已授予时做差量回填（V1.1 §3.2）。
        LedgerKernel.backfillSms(this)
        HealthCheckWorker.enqueue(this)
    }
}
