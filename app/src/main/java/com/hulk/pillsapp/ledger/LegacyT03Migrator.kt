package com.hulk.pillsapp.ledger

import android.content.Context
import android.database.sqlite.SQLiteDatabase

/**
 * T03 既有通知库一次性迁移（V1 §9 M1：保留数据且不静默清除）。
 *
 * 旧库文件 `t03_notification_events.db` 迁移后原样保留在磁盘上；
 * 迁移标记失败时不置位，下次启动重试。重复执行由 (source, source_key)
 * 唯一约束兜底，不会产生重复观察。
 */
object LegacyT03Migrator {
    private const val LEGACY_DB_NAME = "t03_notification_events.db"

    fun run(context: Context, db: LedgerDatabase) {
        if (LedgerKernel.isT03Migrated(context)) return
        val file = context.getDatabasePath(LEGACY_DB_NAME)
        if (file.exists()) {
            try {
                SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { legacy ->
                    legacy.rawQuery(
                        """SELECT notification_key, package_name, posted_at_ms, received_at_ms,
                                  title, body, content_hash
                           FROM notification_events""",
                        null,
                    ).use { cursor ->
                        val now = System.currentTimeMillis()
                        while (cursor.moveToNext()) {
                            db.observationDao().ingest(
                                RawObservationEntity(
                                    source = ObservationSource.NOTIFICATION,
                                    // T03 时代不区分 user_handle，统一归入主空间。
                                    sourceKey = "0:${cursor.getString(0)}",
                                    userHandle = 0,
                                    packageName = cursor.getString(1),
                                    postTimeMs = cursor.getLong(2),
                                    receivedAtMs = cursor.getLong(3),
                                    title = cursor.getString(4),
                                    body = cursor.getString(5),
                                    contentHash = cursor.getString(6),
                                    capturePath = CapturePath.LEGACY_T03_MIGRATION,
                                    parseState = ParseState.PENDING_PARSE,
                                    createdAtMs = now,
                                )
                            )
                        }
                    }
                }
            } catch (_: Throwable) {
                // 迁移失败：不置标记，下次启动重试。
                return
            }
        }
        LedgerKernel.markT03Migrated(context)
    }
}
