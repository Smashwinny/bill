package com.hulk.pillsapp.ledger

import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.hulk.pillsapp.sha256Hex

/**
 * 短信差量回填（V1.1 §3.2）：按收件箱游标拉取历史短信补为观察。
 * 幂等性由 (source, source_key) 唯一约束保证，重复执行不产生重复行。
 * 返回新摄入条数；未授权或失败返回 0，不抛异常。
 */
object SmsBackfill {
    private const val PREFS_NAME = "ledger_kernel_prefs"
    private const val PREF_CURSOR_ID = "sms_backfill_cursor_id"

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) ==
            PackageManager.PERMISSION_GRANTED

    fun run(context: Context): Int {
        if (!hasPermission(context)) return 0
        val prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val cursorId = prefs.getLong(PREF_CURSOR_ID, 0L)
        var maxSeenId = cursorId
        var ingested = 0
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(
                    Telephony.Sms.Inbox._ID,
                    Telephony.Sms.Inbox.ADDRESS,
                    Telephony.Sms.Inbox.DATE,
                    Telephony.Sms.Inbox.DATE_SENT,
                    Telephony.Sms.Inbox.BODY,
                ),
                "${Telephony.Sms.Inbox._ID} > ?",
                arrayOf(cursorId.toString()),
                "${Telephony.Sms.Inbox._ID} ASC",
            )?.use { c ->
                val now = System.currentTimeMillis()
                while (c.moveToNext()) {
                    val providerId = c.getLong(0)
                    val sender = c.getString(1) ?: "unknown"
                    val date = c.getLong(2)
                    val dateSent = c.getLong(3).takeIf { it > 0L } ?: date
                    val body = c.getString(4).orEmpty()
                    LedgerKernel.ingestObservation(
                        RawObservationEntity(
                            source = ObservationSource.SMS,
                            sourceKey = "provider:$providerId",
                            userHandle = 0,
                            packageName = sender,
                            postTimeMs = dateSent,
                            receivedAtMs = now,
                            title = sender,
                            body = body,
                            contentHash = sha256Hex(body),
                            capturePath = CapturePath.SMS_BACKFILL,
                            parseState = ParseState.PENDING_PARSE,
                            createdAtMs = now,
                        )
                    )
                    ingested++
                    if (providerId > maxSeenId) maxSeenId = providerId
                }
            }
        } catch (_: Throwable) {
            return ingested
        }
        prefs.edit().putLong(PREF_CURSOR_ID, maxSeenId).apply()
        return ingested
    }
}
