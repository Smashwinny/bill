package com.hulk.manualledger

import android.app.Application
import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

class ManualLedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ManualSyncScheduler.schedule(this)
    }
}

object ManualSyncScheduler {
    private const val WORK = "manual-ledger-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ManualSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class ManualSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("manual_sync", Context.MODE_PRIVATE)
        val endpoint = prefs.getString("endpoint", null)?.trim().orEmpty()
        if (endpoint.isBlank()) return Result.success() // 未配置云端时，本地 outbox 永不丢弃。
        val database = androidx.room.Room.databaseBuilder(
            applicationContext,
            ManualLedgerDatabase::class.java,
            "manual-ledger.db",
        ).build()
        val dao = database.dao()
        val pending = dao.pendingOutbox(System.currentTimeMillis())
        if (pending.isEmpty()) return Result.success()
        val payload = "{\"schema\":\"${ManualLedgerMigrationCodec.SCHEMA}\",\"events\":[" +
            pending.joinToString(",") { it.payload } + "]}"
        return runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                prefs.getString("bearer_token", null)?.takeIf { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val success = connection.responseCode in 200..299
            connection.disconnect()
            if (success) {
                dao.markSynced(pending.map { it.eventId })
                Result.success()
            } else {
                postpone(dao, pending)
                Result.retry()
            }
        }.getOrElse {
            postpone(dao, pending)
            Result.retry()
        }
    }

    private fun postpone(dao: ManualLedgerDao, events: List<SyncOutboxEntity>) {
        val next = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
        events.forEach { dao.postpone(it.eventId, next) }
    }
}
