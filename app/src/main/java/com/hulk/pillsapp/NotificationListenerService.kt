package com.hulk.pillsapp

import android.service.notification.StatusBarNotification
import android.service.notification.NotificationListenerService as AndroidNotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationListenerService : AndroidNotificationListenerService() {
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerState.setConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        processingScope.launch {
            NotificationEventRepository.persistIfAllowed(this@NotificationListenerService, createNotificationEventFromStatusBarNotification(sbn))
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerState.setDisconnected()
    }

    override fun onDestroy() {
        processingScope.cancel()
        super.onDestroy()
    }
}
