package com.hulk.manualledger

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.room.Room
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val DEFAULT_SYNC_ENDPOINT = "https://ledger.geniusqi.com/v1/sync"

class ManualLedgerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ManualSyncScheduler.schedule(this)
    }
}

data class ManualSyncStatus(
    val configured: Boolean,
    val endpoint: String,
    val lastSuccessAtMs: Long,
    val lastError: String?,
)

data class RemoteLedgerChange(
    val transactionId: String,
    val deleted: Boolean,
    val transaction: ManualTransactionEntity?,
)

object ManualSyncSettings {
    private const val PREFS = "manual_sync"

    fun status(context: Context): ManualSyncStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return ManualSyncStatus(
            configured = SyncSecretStore.read(context) != null,
            endpoint = prefs.getString("endpoint", DEFAULT_SYNC_ENDPOINT) ?: DEFAULT_SYNC_ENDPOINT,
            lastSuccessAtMs = prefs.getLong("last_success_at_ms", 0L),
            lastError = prefs.getString("last_error", null),
        )
    }

    fun configure(context: Context, endpoint: String, token: String) {
        val normalized = endpoint.trim().trimEnd('/')
        require(normalized.startsWith("https://")) { "同步地址必须使用 HTTPS" }
        require(token.trim().length >= 20) { "同步密钥至少需要 20 位" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", normalized)
            .remove("last_error")
            .apply()
        SyncSecretStore.write(context, token.trim())
        ManualSyncScheduler.syncNow(context)
    }

    fun disconnect(context: Context) {
        SyncSecretStore.clear(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove("cursor").remove("last_error").remove("last_success_at_ms").apply()
    }

    internal fun endpoint(context: Context): String = status(context).endpoint
    internal fun token(context: Context): String? = SyncSecretStore.read(context)
    internal fun cursor(context: Context): Long = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("cursor", 0L)
    internal fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString("device_id", null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString("device_id", it).apply() }
    }
    internal fun success(context: Context, cursor: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("cursor", cursor).putLong("last_success_at_ms", System.currentTimeMillis())
            .remove("last_error").apply()
    }
    internal fun failure(context: Context, message: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last_error", message.take(160)).apply()
    }
}

object ManualSyncScheduler {
    private const val PERIODIC_WORK = "manual-ledger-sync"
    private const val IMMEDIATE_WORK = "manual-ledger-sync-now"
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<ManualSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<ManualSyncWorker>().setConstraints(network).build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}

class ManualSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val token = ManualSyncSettings.token(applicationContext) ?: return Result.success()
        val database = Room.databaseBuilder(applicationContext, ManualLedgerDatabase::class.java, "manual-ledger.db").build()
        val dao = database.dao()
        var cursor = ManualSyncSettings.cursor(applicationContext)
        return try {
            repeat(50) {
                val pending = dao.pendingOutbox(System.currentTimeMillis(), 200)
                val response = exchange(token, cursor, pending)
                if (pending.isNotEmpty()) dao.markSynced(pending.map { it.eventId })
                dao.applyRemoteChanges(response.changes)
                if (response.changes.isNotEmpty()) {
                    applicationContext.contentResolver.notifyChange(ManualLedgerProvider.TRANSACTIONS_URI, null)
                }
                cursor = response.cursor
                ManualSyncSettings.success(applicationContext, cursor)
                if (pending.size < 200 && !response.hasMore) return Result.success()
            }
            Result.retry()
        } catch (failure: Exception) {
            val pending = dao.pendingOutbox(System.currentTimeMillis(), 200)
            postpone(dao, pending)
            ManualSyncSettings.failure(applicationContext, failure.message ?: "网络同步失败")
            Result.retry()
        } finally {
            database.close()
        }
    }

    private fun exchange(token: String, cursor: Long, pending: List<SyncOutboxEntity>): SyncResponse {
        val request = JSONObject().put("schema", "manual-ledger-sync-v1")
            .put("device_id", ManualSyncSettings.deviceId(applicationContext))
            .put("cursor", cursor)
            .put("events", JSONArray().apply {
                pending.forEach { event ->
                    put(JSONObject().put("event_id", event.eventId).put("transaction_id", event.transactionId)
                        .put("payload", JSONObject(event.payload)))
                }
            })
        val connection = (URL(ManualSyncSettings.endpoint(applicationContext)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $token")
        }
        connection.outputStream.use { it.write(request.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        check(code in 200..299) { "服务器返回 $code：${body.take(80)}" }
        val root = JSONObject(body)
        val changesJson = root.getJSONArray("changes")
        val changes = ArrayList<RemoteLedgerChange>(changesJson.length())
        repeat(changesJson.length()) { index -> changes += parseRemote(changesJson.getJSONObject(index).getJSONObject("payload")) }
        return SyncResponse(root.getLong("cursor"), root.optBoolean("has_more", false), changes)
    }

    private fun parseRemote(payload: JSONObject): RemoteLedgerChange {
        val id = payload.getString("id")
        if (payload.optBoolean("deleted", false)) return RemoteLedgerChange(id, true, null)
        val updated = payload.getLong("updated_at_ms")
        val entity = ManualTransactionEntity(
            id = id,
            type = ManualTransactionType.valueOf(payload.getString("type")),
            amountCents = payload.getLong("amount_cents"),
            currency = payload.optString("currency", "CNY"),
            category = payload.getString("category").take(40),
            account = payload.getString("account").take(40),
            targetAccount = payload.optNullableString("target_account")?.take(40),
            occurredAtMs = payload.getLong("occurred_at_ms"),
            note = payload.optNullableString("note")?.take(200),
            createdAtMs = updated,
            updatedAtMs = updated,
        )
        return RemoteLedgerChange(id, false, entity)
    }

    private fun postpone(dao: ManualLedgerDao, events: List<SyncOutboxEntity>) {
        val next = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
        events.forEach { dao.postpone(it.eventId, next) }
    }

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else getString(name)

    private data class SyncResponse(val cursor: Long, val hasMore: Boolean, val changes: List<RemoteLedgerChange>)
}

private object SyncSecretStore {
    private const val ALIAS = "manual-ledger-sync-token"
    private const val PREFS = "manual_sync_secret"

    fun write(context: Context, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP)).apply()
    }

    fun read(context: Context): String? = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val encrypted = Base64.decode(prefs.getString("ciphertext", null) ?: return null, Base64.NO_WRAP)
        val iv = Base64.decode(prefs.getString("iv", null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    fun clear(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply() }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
