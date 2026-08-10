package com.hulk.pillsapp

import android.service.notification.NotificationListenerService as AndroidNotificationListenerService

class NotificationListenerService : AndroidNotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationListenerState.setConnected()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationListenerState.setDisconnected()
    }
}
