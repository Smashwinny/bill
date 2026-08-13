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
        thread(name = "ledger-boot-check") {
            try {
                if (!NotificationListenerState.isNotificationListenerEnabled(context)) {
                    LedgerKernel.openGap(GapDetectors.BOOT_CHECK, "开机校验：通知使用权已失效")
                }
                if (!BehaviorAccessibilityState.isEnabled(context) ||
                    !BehaviorAccessibilityState.isServiceConnected() ||
                    !LedgerKernel.isA11yHeartbeatFresh()
                ) {
                    LedgerKernel.openGap(GapDetectors.A11Y_SERVICE, "开机校验：行为学习服务尚未连接或权限已失效")
                }
                HealthCheckWorker.enqueue(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
