package com.hulk.pillsapp

import android.app.Notification
import android.service.notification.StatusBarNotification
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class NotificationEvent(
    val notificationKey: String,
    val packageName: String,
    val postedAtMs: Long,
    val receivedAtMs: Long,
    val title: String,
    val body: String,
    val contentHash: String,
)

fun createNotificationEventFromStatusBarNotification(
    statusBarNotification: StatusBarNotification,
): NotificationEvent {
    val key = statusBarNotification.key.ifBlank {
        "${statusBarNotification.packageName}-${statusBarNotification.postTime}"
    }
    val extras = statusBarNotification.notification.extras
    val title = (extras.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString()
    val body = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: "").toString()
    val hashInput = title + "\u0000" + body
    val contentHash = sha256Hex(hashInput)
    return NotificationEvent(
        notificationKey = key,
        packageName = statusBarNotification.packageName,
        postedAtMs = statusBarNotification.postTime,
        receivedAtMs = System.currentTimeMillis(),
        title = title,
        body = body,
        contentHash = contentHash,
    )
}

fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(input.toByteArray(StandardCharsets.UTF_8))
    return bytes.joinToString("") { b -> "%02x".format(b) }
}
