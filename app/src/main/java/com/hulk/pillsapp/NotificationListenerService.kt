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
        super.onListenerConnected()
        NotificationListenerState.setConnected()
        // 恢复连接：关闭缺口结束时间（缺口记录保留），随后全量补偿扫描。
        LedgerKernel.closeOpenGaps(GapDetectors.LISTENER_CALLBACK)
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
        super.onListenerDisconnected()
        NotificationListenerState.setDisconnected()
        LedgerKernel.openGap(GapDetectors.LISTENER_CALLBACK, "onListenerDisconnected")
    }

    override fun onDestroy() {
        processingScope.cancel()
        super.onDestroy()
    }
}
