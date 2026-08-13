package com.hulk.pillsapp.ledger

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hulk.pillsapp.MainActivity

object BehaviorCandidateNotifier {
    private const val CHANNEL_ID = "behavior_candidate_confirmation"
    private const val NOTIFICATION_BASE = 18_000
    const val EXTRA_CANDIDATE_ID = "candidate_id"
    const val EXTRA_DECISION = "decision"

    fun isAvailableForAuto(context: Context): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        ensureChannel(context)
        val compat = NotificationManagerCompat.from(context)
        if (!compat.areNotificationsEnabled()) return false
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val channel = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(CHANNEL_ID)
            if (channel == null || channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return true
    }

    fun show(context: Context, candidate: BehaviorCandidateEntity) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val amount = candidate.amountCents?.let { cents ->
            "¥%d.%02d".format(cents / 100, kotlin.math.abs(cents % 100))
        } ?: "金额待补充"
        val auto = candidate.state == BehaviorCandidateState.AUTO_RECORDED
        val ambiguous = candidate.ambiguousRepeatCount > 0
        val title = when {
            ambiguous -> "检测到无法区分的重复成功终态"
            auto -> "已按学习规则自动记账"
            else -> "发现可能的${kindLabel(candidate.kind)}"
        }
        val text = "$amount · ${candidate.packageName}"
        val openIntent = PendingIntent.getActivity(
            context,
            notificationId(candidate.id),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$text。${when {
                    ambiguous -> "无法判断是页面刷新还是另一笔交易，模板已暂停自动记账，请打开应用核对。"
                    auto -> "如有误请撤销，模板会立即停止自动记账。"
                    else -> "请确认后才会进入正式成功账。"
                }}"
            ))
            .setContentIntent(openIntent)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        if (auto) {
            builder.addAction(0, "撤销", actionIntent(context, candidate.id, BehaviorDecision.UNDO_AUTO))
        } else if (candidate.state == BehaviorCandidateState.PENDING) {
            builder
                .addAction(0, "确认付款", actionIntent(context, candidate.id, BehaviorDecision.CONFIRM_PAYMENT))
                .addAction(0, "确认退款", actionIntent(context, candidate.id, BehaviorDecision.CONFIRM_REFUND))
                .addAction(0, "不是", actionIntent(context, candidate.id, BehaviorDecision.REJECT))
        }
        runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(candidate.id), builder.build())
        }
    }

    fun cancel(context: Context, candidateId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(candidateId))
    }

    private fun actionIntent(
        context: Context,
        candidateId: Long,
        decision: BehaviorDecision,
    ): PendingIntent {
        val intent = Intent(context, BehaviorCandidateActionReceiver::class.java).apply {
            data = Uri.parse("pills://behavior/$candidateId/${decision.name}")
            putExtra(EXTRA_CANDIDATE_ID, candidateId)
            putExtra(EXTRA_DECISION, decision.name)
        }
        return PendingIntent.getBroadcast(
            context,
            notificationId(candidateId) * 10 + decision.ordinal,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "行为记账确认",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "疑似付款、退款确认和自动记账撤销"
            }
        )
    }

    private fun notificationId(id: Long): Int = NOTIFICATION_BASE + (id % 10_000).toInt()

    private fun kindLabel(kind: BehaviorKind): String =
        if (kind == BehaviorKind.REFUND) "退款" else "付款"
}

class BehaviorCandidateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val candidateId = intent.getLongExtra(BehaviorCandidateNotifier.EXTRA_CANDIDATE_ID, -1L)
        val decision = intent.getStringExtra(BehaviorCandidateNotifier.EXTRA_DECISION)
            ?.let { runCatching { BehaviorDecision.valueOf(it) }.getOrNull() }
        if (candidateId <= 0L || decision == null || decision == BehaviorDecision.AUTO_RECORD) return
        val pending = goAsync()
        LedgerKernel.applyBehaviorDecisionAsync(candidateId, decision, pending::finish)
    }
}
