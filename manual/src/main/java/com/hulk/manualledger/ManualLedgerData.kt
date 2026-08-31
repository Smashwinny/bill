package com.hulk.manualledger

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

enum class ManualTransactionType { EXPENSE, INCOME, TRANSFER }
enum class OutboxState { PENDING, SYNCED }

@Entity(tableName = "manual_transaction")
data class ManualTransactionEntity(
    @PrimaryKey val id: String,
    val type: ManualTransactionType,
    @ColumnInfo(name = "amount_cents") val amountCents: Long,
    val currency: String = "CNY",
    val category: String,
    val account: String,
    @ColumnInfo(name = "target_account") val targetAccount: String?,
    @ColumnInfo(name = "occurred_at_ms") val occurredAtMs: Long,
    val note: String?,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "sync_outbox")
data class SyncOutboxEntity(
    @PrimaryKey @ColumnInfo(name = "event_id") val eventId: String,
    @ColumnInfo(name = "transaction_id") val transactionId: String,
    val payload: String,
    val state: OutboxState,
    val attempts: Int,
    @ColumnInfo(name = "next_attempt_at_ms") val nextAttemptAtMs: Long,
    @ColumnInfo(name = "created_at_ms") val createdAtMs: Long,
)

@Dao
abstract class ManualLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertTransactionIgnore(entity: ManualTransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract fun insertOutbox(entity: SyncOutboxEntity)

    @Query("SELECT * FROM manual_transaction ORDER BY occurred_at_ms DESC, created_at_ms DESC")
    abstract fun listTransactions(): List<ManualTransactionEntity>

    @Query("SELECT * FROM sync_outbox WHERE state = 'PENDING' AND next_attempt_at_ms <= :nowMs ORDER BY created_at_ms LIMIT :limit")
    abstract fun pendingOutbox(nowMs: Long, limit: Int = 50): List<SyncOutboxEntity>

    @Query("UPDATE sync_outbox SET state = 'SYNCED' WHERE event_id IN (:ids) AND state = 'PENDING'")
    abstract fun markSynced(ids: List<String>): Int

    @Query("UPDATE sync_outbox SET attempts = attempts + 1, next_attempt_at_ms = :nextMs WHERE event_id = :id AND state = 'PENDING'")
    abstract fun postpone(id: String, nextMs: Long): Int

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE state = 'PENDING'")
    abstract fun pendingCount(): Long

    @Query("DELETE FROM manual_transaction WHERE id = :id")
    abstract fun deleteTransaction(id: String): Int
}

@Database(entities = [ManualTransactionEntity::class, SyncOutboxEntity::class], version = 1, exportSchema = true)
abstract class ManualLedgerDatabase : RoomDatabase() {
    abstract fun dao(): ManualLedgerDao
}

data class NewManualTransaction(
    val stableId: String? = null,
    val type: ManualTransactionType,
    val amountText: String,
    val category: String,
    val account: String,
    val targetAccount: String? = null,
    val occurredAtMs: Long = System.currentTimeMillis(),
    val note: String? = null,
)

class ManualLedgerRepository(private val db: ManualLedgerDatabase) {
    fun add(input: NewManualTransaction): ManualTransactionEntity {
        val cents = parseCents(input.amountText) ?: error("金额格式错误")
        require(input.category.isNotBlank()) { "分类不能为空" }
        require(input.account.isNotBlank()) { "账户不能为空" }
        val now = System.currentTimeMillis()
        val tx = ManualTransactionEntity(
            id = input.stableId ?: UUID.randomUUID().toString(),
            type = input.type,
            amountCents = cents,
            category = input.category.trim().take(40),
            account = input.account.trim().take(40),
            targetAccount = input.targetAccount?.trim()?.take(40)?.ifBlank { null },
            occurredAtMs = input.occurredAtMs,
            note = input.note?.trim()?.take(200)?.ifBlank { null },
            createdAtMs = now,
            updatedAtMs = now,
        )
        val event = SyncOutboxEntity(
            eventId = UUID.randomUUID().toString(),
            transactionId = tx.id,
            payload = ManualLedgerMigrationCodec.transactionJson(tx),
            state = OutboxState.PENDING,
            attempts = 0,
            nextAttemptAtMs = now,
            createdAtMs = now,
        )
        db.runInTransaction {
            if (db.dao().insertTransactionIgnore(tx) != -1L) {
                db.dao().insertOutbox(event)
            }
        }
        return tx
    }

    fun list(): List<ManualTransactionEntity> = db.dao().listTransactions()
    fun pendingSyncCount(): Long = db.dao().pendingCount()

    fun delete(id: String) {
        val now = System.currentTimeMillis()
        val event = SyncOutboxEntity(
            eventId = UUID.randomUUID().toString(),
            transactionId = id,
            payload = "{\"schema\":\"${ManualLedgerMigrationCodec.SCHEMA}\",\"id\":\"${jsonId(id)}\",\"deleted\":true,\"updated_at_ms\":$now}",
            state = OutboxState.PENDING,
            attempts = 0,
            nextAttemptAtMs = now,
            createdAtMs = now,
        )
        db.runInTransaction {
            if (db.dao().deleteTransaction(id) > 0) db.dao().insertOutbox(event)
        }
    }

    companion object {
        fun open(context: Context): ManualLedgerRepository {
            val db = Room.databaseBuilder(
                context.applicationContext,
                ManualLedgerDatabase::class.java,
                "manual-ledger.db",
            ).build()
            return ManualLedgerRepository(db)
        }

        fun parseCents(raw: String): Long? = runCatching {
            BigDecimal(raw.trim()).setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact().takeIf { it > 0 }
        }.getOrNull()

        private fun jsonId(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}

object ManualLedgerMigrationCodec {
    const val SCHEMA = "manual-ledger-v1"

    fun exportJson(rows: List<ManualTransactionEntity>): String = buildString {
        append("{\"schema\":\"").append(SCHEMA).append("\",\"exported_at\":\"")
            .append(Instant.now()).append("\",\"transactions\":[")
        rows.forEachIndexed { index, row ->
            if (index > 0) append(',')
            append(transactionJson(row))
        }
        append("]}")
    }

    fun transactionJson(row: ManualTransactionEntity): String = buildString {
        append("{\"id\":\"").append(json(row.id)).append("\",\"type\":\"")
            .append(row.type.name).append("\",\"amount_cents\":").append(row.amountCents)
            .append(",\"currency\":\"").append(row.currency).append("\",\"category\":\"")
            .append(json(row.category)).append("\",\"account\":\"").append(json(row.account))
            .append("\",\"target_account\":")
        row.targetAccount?.let { append('"').append(json(it)).append('"') } ?: append("null")
        append(",\"occurred_at_ms\":").append(row.occurredAtMs).append(",\"note\":")
        row.note?.let { append('"').append(json(it)).append('"') } ?: append("null")
        append(",\"updated_at_ms\":").append(row.updatedAtMs).append('}')
    }

    private fun json(value: String): String = value
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r")
}
