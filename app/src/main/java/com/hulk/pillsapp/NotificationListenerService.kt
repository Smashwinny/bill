package com.hulk.pillsapp

import android.service.notification.StatusBarNotification
import com.hulk.pillsapp.ledger.CapturePath
import com.hulk.pillsapp.ledger.GapDetectors
import com.hulk.pillsapp.ledger.LedgerKernel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.service.notification.NotificationListenerService as AndroidNotificationListenerService

/**
 * V1.1 §3.1/§4：内核同步落盘必须先于任何异步处理返回。
 * T03/T04 探针链路保留（白名单门控、内存态会话），仅作可行性探针（V1 决策 10）。
 */
class NotificationListenerService : AndroidNotificationListenerService() {
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        val connectedAtMs = System.currentTimeMillis()
        super.onListenerConnected()
        NotificationListenerState.setConnected()
        // 生命周期回调不等待共享队列；关闭缺口与补偿扫描按同一执行器顺序入队。
        LedgerKernel.closeOpenGapsAsync(GapDetectors.LISTENER_CALLBACK, connectedAtMs)
        LedgerKernel.sweepActiveNotifications(activeNotifications?.toList().orEmpty())
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        // 第一优先级：同步写入完整性内核，不在此处 launch 协程后再写库。
        LedgerKernel.ingestNotification(sbn, CapturePath.LIVE_CALLBACK)
        // 第二优先级：T03/T04 探针（异步、尽力而为）。
        val event = createNotificationEventFromStatusBarNotification(sbn)
        processingScope.launch {
            NotificationEventRepository.persistIfAllowed(this@NotificationListenerService, event)
        }
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification?,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        if (sbn != null) {
            LedgerKernel.recordRemoval(sbn, reason)
        }
    }

    override fun onListenerDisconnected() {
        val disconnectedAtMs = System.currentTimeMillis()
        super.onListenerDisconnected()
        NotificationListenerState.setDisconnected()
        LedgerKernel.openGapAsync(
            GapDetectors.LISTENER_CALLBACK,
            "onListenerDisconnected",
            disconnectedAtMs,
        )
    }

    override fun onDestroy() {
        processingScope.cancel()
        super.onDestroy()
    }
}
