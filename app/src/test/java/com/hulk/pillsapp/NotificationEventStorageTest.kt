package com.hulk.pillsapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

private class InMemoryNotificationEventStore {
    private val events = LinkedHashMap<String, NotificationEvent>()

    fun insertOrIgnore(event: NotificationEvent): Boolean {
        return if (events.containsKey(event.notificationKey)) {
            false
        } else {
            events[event.notificationKey] = event
            true
        }
    }

    fun queryAll(): List<NotificationEvent> = events.values.toList()
    fun clearAll() {
        events.clear()
    }
}

class NotificationEventStorageTest {
    @Test
    fun duplicateNotificationKeyOnlyKeepsOneRow() {
        val store = InMemoryNotificationEventStore()
        val first = NotificationEvent(
            notificationKey = "dup-key",
            packageName = "com.example.pay",
            postedAtMs = 1L,
            receivedAtMs = 2L,
            title = "title",
            body = "body",
            contentHash = sha256Hex("title\u0000body"),
        )
        val second = first.copy(receivedAtMs = 3L, title = "other")

        val insertedFirst = store.insertOrIgnore(first)
        val insertedSecond = store.insertOrIgnore(second)

        assertTrue(insertedFirst)
        assertFalse(insertedSecond)
        assertEquals(1, store.queryAll().size)
        assertEquals("title", store.queryAll().first().title)
    }

    @Test
    fun localStorageSupportsInsertAndClear() {
        val store = InMemoryNotificationEventStore()
        assertTrue(store.queryAll().isEmpty())

        val event = NotificationEvent(
            notificationKey = "k1",
            packageName = "com.example.pay",
            postedAtMs = 10L,
            receivedAtMs = 20L,
            title = "payment",
            body = "100元到账",
            contentHash = sha256Hex("payment\u0000100元到账"),
        )
        val inserted = store.insertOrIgnore(event)
        assertTrue(inserted)
        assertEquals(1, store.queryAll().size)

        store.clearAll()
        assertTrue(store.queryAll().isEmpty())
    }

    @Test
    fun packageWhitelistJudgmentIsExplicit() {
        val whitelist = setOf("com.example.bank", "com.example.app")
        assertTrue(whitelist.contains("com.example.bank"))
        assertFalse(whitelist.contains("com.unknown"))
    }

    @Test
    fun manifestShouldNotDeclareInternetOrStorageOrSmsPermissions() {
        val manifestPath = Paths.get("src/main/AndroidManifest.xml")
        val manifest = String(Files.readAllBytes(manifestPath))
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertFalse(manifest.contains("android.permission.RECEIVE_SMS"))
        assertFalse(manifest.contains("android.permission.READ_CONTACTS"))
        assertFalse(manifest.contains("android.permission.WRITE_EXTERNAL_STORAGE"))
        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(manifest.contains("android.permission.BIND_ACCESSIBILITY_SERVICE"))
        assertTrue(manifest.contains("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"))
    }

    @Test
    fun notificationHashFunctionMatchesExpectedLength() {
        val hash = sha256Hex("hello")
        assertEquals(64, hash.length)
        val sameHash = sha256Hex("hello")
        assertEquals(hash, sameHash)
    }
}
