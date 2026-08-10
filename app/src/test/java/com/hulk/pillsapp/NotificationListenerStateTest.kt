package com.hulk.pillsapp

import org.junit.Assert.assertEquals
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
}
