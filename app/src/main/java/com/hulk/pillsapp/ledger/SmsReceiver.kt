package com.hulk.pillsapp.ledger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.hulk.pillsapp.sha256Hex

/**
 * 银行短信实时观察（V1.1 §3.2）。
 * onReceive 内同步落盘（V1.1 §4 统一管线），解析异步。
 * 隐私约束：发件人与正文只进加密库（package_name 字段复用为发件人），
 * 不写日志、不进 Logcat（T00 §5）。
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val pduFingerprint = (intent.extras?.get("pdus") as? Array<*>)
            ?.filterIsInstance<ByteArray>()
            ?.fold(java.security.MessageDigest.getInstance("SHA-256")) { digest, pdu ->
                digest.apply { update(pdu) }
            }
            ?.digest()
            ?.joinToString("") { "%02x".format(it) }
        // 长短信分片按发件人归并
        messages.groupBy { it.originatingAddress ?: "unknown" }.forEach { (sender, parts) ->
            val body = parts.joinToString("") { it.messageBody.orEmpty() }
            val timestamp = parts.first().timestampMillis
            val now = System.currentTimeMillis()
            LedgerKernel.ingestObservation(
                RawObservationEntity(
                    source = ObservationSource.SMS,
                    sourceKey = "broadcast:${pduFingerprint ?: sha256Hex("$sender\u0000$timestamp\u0000$body")}",
                    userHandle = 0,
                    packageName = sender,
                    postTimeMs = timestamp,
                    receivedAtMs = now,
                    title = sender,
                    body = body,
                    contentHash = sha256Hex(body),
                    capturePath = CapturePath.SMS_BROADCAST,
                    parseState = ParseState.PENDING_PARSE,
                    createdAtMs = now,
                )
            )
        }
    }
}
