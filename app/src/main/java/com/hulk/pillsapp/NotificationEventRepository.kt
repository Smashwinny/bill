package com.hulk.pillsapp

import android.content.ContentValues
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PREF_KEY_PACKAGE_WHITELIST = "t03_package_whitelist"
private const val PREFS_NAME = "notification_t03_prefs"

private class NotificationEventDbHelper(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext,
    "t03_notification_events.db",
    null,
    1
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS notification_events (
                notification_key TEXT PRIMARY KEY,
                package_name TEXT NOT NULL,
                posted_at_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                title TEXT NOT NULL,
                body TEXT NOT NULL,
                content_hash TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // T03 only. keep schema stable.
    }

    fun upsertWithDuplicateState(event: NotificationEvent): Pair<Boolean, Boolean> {
        val existsBeforeInsert = exists(event.notificationKey)
        val values = ContentValues().apply {
            put("notification_key", event.notificationKey)
            put("package_name", event.packageName)
            put("posted_at_ms", event.postedAtMs)
            put("received_at_ms", event.receivedAtMs)
            put("title", event.title)
            put("body", event.body)
            put("content_hash", event.contentHash)
        }
        val db = writableDatabase
        val result = db.insertWithOnConflict(
            "notification_events",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )
        return result != -1L to existsBeforeInsert
    }

    private fun exists(notificationKey: String): Boolean {
        val cursor = readableDatabase.query(
            "notification_events",
            arrayOf("1"),
            "notification_key = ?",
            arrayOf(notificationKey),
            null,
            null,
            null,
            "1",
        )
        return cursor.use { it.moveToFirst() }
    }

    fun queryAll(): List<NotificationEvent> {
        val db = readableDatabase
        val cursor: Cursor = db.query(
            "notification_events",
            arrayOf(
                "notification_key",
                "package_name",
                "posted_at_ms",
                "received_at_ms",
                "title",
                "body",
                "content_hash"
            ),
            null,
            null,
            null,
            null,
            "received_at_ms DESC"
        )
        return cursor.use {
            val items = ArrayList<NotificationEvent>(it.count)
            val keyIndex = it.getColumnIndexOrThrow("notification_key")
            val packageIndex = it.getColumnIndexOrThrow("package_name")
            val postedIndex = it.getColumnIndexOrThrow("posted_at_ms")
            val receivedIndex = it.getColumnIndexOrThrow("received_at_ms")
            val titleIndex = it.getColumnIndexOrThrow("title")
            val bodyIndex = it.getColumnIndexOrThrow("body")
            val hashIndex = it.getColumnIndexOrThrow("content_hash")
            while (it.moveToNext()) {
                items.add(
                    NotificationEvent(
                        notificationKey = it.getString(keyIndex),
                        packageName = it.getString(packageIndex),
                        postedAtMs = it.getLong(postedIndex),
                        receivedAtMs = it.getLong(receivedIndex),
                        title = it.getString(titleIndex),
                        body = it.getString(bodyIndex),
                        contentHash = it.getString(hashIndex),
                    )
                )
            }
            items
        }
    }

    fun clearAll() {
        writableDatabase.delete("notification_events", null, null)
    }
}

object NotificationEventRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _events = MutableStateFlow<List<NotificationEvent>>(emptyList())
    private val _enabledPackages = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<NotificationEvent>> = _events.asStateFlow()
    val enabledPackages: StateFlow<List<String>> = _enabledPackages.asStateFlow()
    private val dbHelperCache = mutableMapOf<String, NotificationEventDbHelper>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    fun refreshPermissionPackages(context: Context) {
        val current = prefs(context).getStringSet(PREF_KEY_PACKAGE_WHITELIST, emptySet()) ?: emptySet()
        _enabledPackages.value = current.toList().sorted()
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        val current = prefs(context).getStringSet(PREF_KEY_PACKAGE_WHITELIST, emptySet()) ?: emptySet()
        return current.contains(packageName)
    }

    fun addEnabledPackage(context: Context, packageName: String) {
        val current = prefs(context).getStringSet(PREF_KEY_PACKAGE_WHITELIST, emptySet()) ?: emptySet()
        val next = HashSet(current)
        next.add(packageName)
        prefs(context).edit().putStringSet(PREF_KEY_PACKAGE_WHITELIST, next).apply()
        refreshPermissionPackages(context)
    }

    fun removeEnabledPackage(context: Context, packageName: String) {
        val current = prefs(context).getStringSet(PREF_KEY_PACKAGE_WHITELIST, emptySet()) ?: emptySet()
        val next = HashSet(current)
        next.remove(packageName)
        prefs(context).edit().putStringSet(PREF_KEY_PACKAGE_WHITELIST, next).apply()
        refreshPermissionPackages(context)
    }

    fun refreshEvents(context: Context) {
        scope.launch {
            val items = getDbHelper(context).queryAll()
            _events.emit(items)
        }
    }

    fun persistIfAllowed(context: Context, event: NotificationEvent) {
        if (!isPackageEnabled(context, event.packageName)) {
            return
        }
        scope.launch {
            val (inserted, keyReused) = getDbHelper(context).upsertWithDuplicateState(event)
            ProbeSessionRepository.onNotificationEvent(event, keyReused)
            if (inserted) {
                _events.emit(getDbHelper(context).queryAll())
            }
        }
    }

    fun clearAll(context: Context) {
        scope.launch {
            getDbHelper(context).clearAll()
            _events.emit(emptyList())
        }
    }

    fun closeDbHelpers() {
        dbHelperCache.values.forEach { it.close() }
        dbHelperCache.clear()
        scope.cancel()
    }

    private fun getDbHelper(context: Context): NotificationEventDbHelper {
        val appContext = context.applicationContext
        val key = appContext.javaClass.name + "@" + System.identityHashCode(appContext)
        return dbHelperCache.getOrPut(key) { NotificationEventDbHelper(appContext) }
    }
}
