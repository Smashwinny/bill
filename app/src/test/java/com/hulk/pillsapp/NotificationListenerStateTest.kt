package com.hulk.pillsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths
import java.time.ZoneId

class NotificationListenerStateTest {
    @Test
    fun permissionAndServiceLabelsShouldMapToReadableText() {
        assertEquals("已开启", NotificationListenerState.permissionLabel(true))
        assertEquals("未开启", NotificationListenerState.permissionLabel(false))
        assertEquals("已连接", NotificationListenerState.serviceConnectionLabel(true))
        assertEquals("未连接", NotificationListenerState.serviceConnectionLabel(false))
    }

    @Test
    fun connectionTimeShouldFormatOrMarkNever() {
        assertEquals("未发生", formatConnectionTime(null))

        val fixedMillis = 0L
        val formatted = formatConnectionTime(fixedMillis, ZoneId.of("UTC"))
        assertEquals("1970-01-01 00:00:00", formatted)
    }

    @Test
    fun manifestShouldDeclareNotificationListenerServiceAndAction() {
        val manifestPath = Paths.get("src/main/AndroidManifest.xml")
        val manifest = String(Files.readAllBytes(manifestPath))

        assertNotNull(manifest)
        assertTrue(manifest.contains("android:name=\".NotificationListenerService\""))
        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE\""))
        assertTrue(manifest.contains("android.service.notification.NotificationListenerService"))
    }

    @Test
    fun t03RequiresNoForbiddenPermissionsInManifest() {
        val manifestPath = Paths.get("src/main/AndroidManifest.xml")
        val manifest = String(Files.readAllBytes(manifestPath))

        assertTrue(manifest.contains("android:permission=\"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE\""))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        // V1.1 §3.2 起 READ_SMS/RECEIVE_SMS 为正式采集通道（侧载直申）；
        // SEND_SMS 永久禁止——本应用只读短信，绝不发送。
        assertFalse(manifest.contains("android.permission.SEND_SMS"))
        assertFalse(manifest.contains("android.permission.READ_CONTACTS"))
        assertFalse(manifest.contains("android.permission.WRITE_CONTACTS"))
        assertFalse(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        // M5 允许系统绑定专用行为学习服务，但仍禁止录屏/悬浮窗通道。
        assertTrue(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertFalse(manifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"))
    }
}
