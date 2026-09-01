package com.hulk.manualledger

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import androidx.room.Update
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.DayOfWeek
import java.time.YearMonth
import java.util.UUID

enum class ManualTransactionType { EXPENSE, INCOME, TRANSFER }
enum class OutboxState { PENDING, SYNCED }

object LedgerInsights {
    data class TimePeak(val label: String, val count: Int, val amountCents: Long)
    data class DailySpend(val dayOfMonth: Int, val amountCents: Long)

    fun projectedExpense(expenseCents: Long, elapsedDays: Int, monthDays: Int): Long {
        if (expenseCents <= 0 || elapsedDays <= 0 || monthDays <= 0) return 0
        return BigDecimal.valueOf(expenseCents).multiply(BigDecimal.valueOf(monthDays.toLong()))
            .divide(BigDecimal.valueOf(elapsedDays.toLong()), 0, RoundingMode.DOWN)
            .longValueExact()
    }

    fun monthChangePercent(currentCents: Long, previousCents: Long): Int? {
        if (previousCents <= 0) return null
        return BigDecimal.valueOf(currentCents - previousCents).multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(previousCents), 0, RoundingMode.DOWN)
            .intValueExact()
    }

    fun peakTime(expenses: List<ManualTransactionEntity>, zone: ZoneId = ZoneId.systemDefault()): TimePeak? {
        return expenses.groupBy { timeLabel(Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).hour) }
            .map { (label, rows) -> TimePeak(label, rows.size, rows.sumOf { it.amountCents }) }
            .maxWithOrNull(compareBy<TimePeak> { it.count }.thenBy { it.amountCents })
    }

    fun weekendSharePercent(expenses: List<ManualTransactionEntity>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val total = expenses.sumOf { it.amountCents }
        if (total <= 0) return 0
        val weekend = expenses.filter {
            Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        }.sumOf { it.amountCents }
        return (weekend * 100 / total).toInt()
    }

    fun dailySpending(
        expenses: List<ManualTransactionEntity>,
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<DailySpend> {
        val totals = expenses.filter { it.type == ManualTransactionType.EXPENSE }
            .filter { YearMonth.from(Instant.ofEpochMilli(it.occurredAtMs).atZone(zone)) == month }
            .groupBy { Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).dayOfMonth }
            .mapValues { (_, rows) -> rows.sumOf { it.amountCents } }
        return (1..month.lengthOfMonth()).map { day -> DailySpend(day, totals[day] ?: 0L) }
    }

    private fun timeLabel(hour: Int): String = when (hour) {
        in 0..5 -> "深夜 0–5点"
        in 6..9 -> "早间 6–9点"
        in 10..11 -> "上午 10–11点"
        in 12..13 -> "午间 12–13点"
        in 14..17 -> "下午 14–17点"
        else -> "晚间 18–23点"
    }
}

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
    @ColumnInfo(name = "category_id") val categoryId: String? = null,
)

@Entity(
    tableName = "ledger_category",
    indices = [Index(value = ["type", "parent_id", "name"], unique = true), Index("parent_id")],
)
data class LedgerCategoryEntity(
    @PrimaryKey val id: String,
    val type: ManualTransactionType,
    val name: String,
    @ColumnInfo(name = "parent_id") val parentId: String?,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "is_system") val isSystem: Boolean,
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

    @Query("SELECT * FROM manual_transaction WHERE id = :id LIMIT 1")
    abstract fun findTransaction(id: String): ManualTransactionEntity?

    @Update
    abstract fun updateTransaction(entity: ManualTransactionEntity): Int

    @Query("UPDATE manual_transaction SET category_id = :categoryId WHERE id = :transactionId")
    abstract fun bindTransactionCategory(transactionId: String, categoryId: String): Int

    @Query("SELECT * FROM manual_transaction WHERE category_id IN (:categoryIds)")
    abstract fun transactionsInCategories(categoryIds: List<String>): List<ManualTransactionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract fun insertCategoryIgnore(entity: LedgerCategoryEntity): Long

    @Update
    abstract fun updateCategory(entity: LedgerCategoryEntity): Int

    @Query("SELECT * FROM ledger_category ORDER BY type, parent_id, sort_order, name")
    abstract fun listCategories(): List<LedgerCategoryEntity>

    @Query("SELECT * FROM ledger_category WHERE id = :id LIMIT 1")
    abstract fun findCategory(id: String): LedgerCategoryEntity?

    @Query("DELETE FROM ledger_category WHERE id IN (:ids) AND is_system = 0")
    abstract fun deleteCategories(ids: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract fun upsertRemoteTransaction(entity: ManualTransactionEntity)

    @Transaction
    open fun applyRemoteChanges(changes: List<RemoteLedgerChange>) {
        changes.forEach { change ->
            if (change.deleted) deleteTransaction(change.transactionId)
            else change.transaction?.let { remote ->
                val local = findTransaction(remote.id)
                upsertRemoteTransaction(remote.copy(
                    categoryId = local?.categoryId?.takeIf { local.category == remote.category },
                ))
            }
        }
    }
}

@Database(
    entities = [ManualTransactionEntity::class, SyncOutboxEntity::class, LedgerCategoryEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class ManualLedgerDatabase : RoomDatabase() {
    abstract fun dao(): ManualLedgerDao
}

val MANUAL_LEDGER_MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE manual_transaction ADD COLUMN category_id TEXT")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ledger_category (
                id TEXT NOT NULL PRIMARY KEY,
                type TEXT NOT NULL,
                name TEXT NOT NULL,
                parent_id TEXT,
                sort_order INTEGER NOT NULL,
                is_system INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_category_type_parent_id_name ON ledger_category(type, parent_id, name)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_category_parent_id ON ledger_category(parent_id)")
    }
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
    val preserveCategoryPath: Boolean = false,
)

data class ImportStats(val inserted: Int, val enriched: Int, val unchanged: Int)
data class CategoryImpact(val categoryCount: Int, val transactionCount: Int)
data class CategoryMutationResult(val movedTransactions: Int, val removedCategories: Int = 0)

class ManualLedgerRepository internal constructor(private val db: ManualLedgerDatabase) {
    fun add(input: NewManualTransaction): Boolean {
        val (tx, event) = prepare(input, System.currentTimeMillis())
        var inserted = false
        db.runInTransaction {
            if (db.dao().insertTransactionIgnore(tx) != -1L) {
                db.dao().insertOutbox(event)
                inserted = true
            }
        }
        if (inserted) ensureCategoryTree()
        return inserted
    }

    fun import(rows: List<NewManualTransaction>): ImportStats {
        var inserted = 0
        var enriched = 0
        var unchanged = 0
        val base = System.currentTimeMillis()
        db.runInTransaction {
            rows.forEachIndexed { index, input ->
                val (tx, event) = prepare(input, base + index)
                if (db.dao().insertTransactionIgnore(tx) != -1L) {
                    db.dao().insertOutbox(event)
                    inserted++
                } else {
                    val original = db.dao().findTransaction(tx.id)
                    val changed = original != null && (
                        original.type != tx.type || original.amountCents != tx.amountCents ||
                            original.category != tx.category || original.account != tx.account ||
                            original.targetAccount != tx.targetAccount || original.occurredAtMs != tx.occurredAtMs ||
                            original.note != tx.note
                        )
                    val updated = original?.copy(
                        type = tx.type,
                        amountCents = tx.amountCents,
                        category = tx.category,
                        account = tx.account,
                        targetAccount = tx.targetAccount,
                        occurredAtMs = tx.occurredAtMs,
                        note = tx.note,
                        updatedAtMs = tx.updatedAtMs,
                        categoryId = original.categoryId?.takeIf { original.category == tx.category },
                    )
                    if (changed && updated != null) {
                        db.dao().updateTransaction(updated)
                        db.dao().insertOutbox(event.copy(payload = ManualLedgerMigrationCodec.transactionJson(updated)))
                        enriched++
                    } else unchanged++
                }
            }
        }
        ensureCategoryTree()
        return ImportStats(inserted, enriched, unchanged)
    }

    private fun prepare(input: NewManualTransaction, now: Long): Pair<ManualTransactionEntity, SyncOutboxEntity> {
        val cents = parseCents(input.amountText) ?: error("金额格式错误")
        require(input.category.isNotBlank()) { "分类不能为空" }
        require(input.account.isNotBlank()) { "账户不能为空" }
        val tx = ManualTransactionEntity(
            id = input.stableId ?: UUID.randomUUID().toString(),
            type = input.type,
            amountCents = cents,
            category = if (input.preserveCategoryPath || CategoryCatalog.HIERARCHY_SEPARATOR in input.category) input.category.trim().take(80)
                else CategoryCatalog.normalize(input.type, input.category),
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
        return tx to event
    }

    fun list(): List<ManualTransactionEntity> {
        ensureCategoryTree()
        return db.dao().listTransactions()
    }
    fun categories(): List<LedgerCategoryEntity> {
        ensureCategoryTree()
        return db.dao().listCategories()
    }
    fun pendingSyncCount(): Long = db.dao().pendingCount()

    fun ensureCategoryTree() {
        db.runInTransaction {
            val dao = db.dao()
            val categories = dao.listCategories().associateByTo(linkedMapOf()) { it.id }
            val now = System.currentTimeMillis()
            fun ensure(type: ManualTransactionType, name: String, parentId: String?, order: Int, system: Boolean): String {
                val existing = categories.values.firstOrNull { it.type == type && it.parentId == parentId && it.name == name }
                if (existing != null) return existing.id
                val id = UUID.nameUUIDFromBytes("manual-category-v1:${type.name}:${parentId.orEmpty()}:$name".toByteArray()).toString()
                val node = LedgerCategoryEntity(id, type, name.take(40), parentId, order, system, now, now)
                dao.insertCategoryIgnore(node)
                categories[id] = dao.findCategory(id) ?: node
                return id
            }
            ManualTransactionType.entries.forEach { type ->
                ensure(type, UNCATEGORIZED_NAME, null, Int.MAX_VALUE, true)
                CategoryCatalog.hierarchyOptions(type).entries.forEachIndexed { primaryOrder, entry ->
                    val primaryId = ensure(type, entry.key, null, primaryOrder, false)
                    entry.value.filter { it != entry.key }.forEachIndexed { childOrder, child ->
                        ensure(type, child, primaryId, childOrder, false)
                    }
                }
            }
            dao.listTransactions().forEach { tx ->
                if (tx.categoryId != null && categories.containsKey(tx.categoryId)) return@forEach
                var parentId: String? = null
                categorySegments(tx.category).forEachIndexed { index, name ->
                    parentId = ensure(tx.type, name, parentId, index, false)
                }
                val leafId = parentId ?: ensure(tx.type, UNCATEGORIZED_NAME, null, Int.MAX_VALUE, true)
                dao.bindTransactionCategory(tx.id, leafId)
            }
            // Older imports could bind a transaction directly to a category that later
            // gained children. Such a node is hidden by the leaf-only picker, so retain
            // the hierarchy while exposing those transactions through a real leaf.
            dao.listCategories().mapNotNull { it.parentId }.distinct().forEachIndexed { index, parentId ->
                rehomeDirectTransactionsToUnspecified(parentId, now + index)
            }
        }
    }

    fun categoryImpact(categoryId: String): CategoryImpact {
        ensureCategoryTree()
        val categories = db.dao().listCategories()
        val ids = descendantIds(categoryId, categories)
        return CategoryImpact(ids.size, if (ids.isEmpty()) 0 else db.dao().transactionsInCategories(ids).size)
    }

    fun createCategory(type: ManualTransactionType, name: String, parentId: String?): LedgerCategoryEntity {
        ensureCategoryTree()
        val clean = name.trim().take(40)
        require(clean.isNotBlank()) { "分类名称不能为空" }
        val categories = db.dao().listCategories()
        val parent = parentId?.let { id -> categories.firstOrNull { it.id == id } ?: error("上级分类不存在") }
        require(parent == null || parent.type == type) { "上下级分类类型不一致" }
        require(categories.none { it.type == type && it.parentId == parentId && it.name == clean }) { "同级已有同名分类" }
        val now = System.currentTimeMillis()
        val created = LedgerCategoryEntity(
            UUID.randomUUID().toString(), type, clean, parentId,
            categories.count { it.parentId == parentId }, false, now, now,
        )
        db.runInTransaction {
            parentId?.let { rehomeDirectTransactionsToUnspecified(it, now) }
            db.dao().insertCategoryIgnore(created)
        }
        return created
    }

    fun moveCategory(categoryId: String, newParentId: String?): CategoryMutationResult {
        ensureCategoryTree()
        var result = CategoryMutationResult(0)
        db.runInTransaction {
            val dao = db.dao()
            val categories = dao.listCategories()
            val source = categories.firstOrNull { it.id == categoryId } ?: error("分类不存在")
            require(!source.isSystem) { "系统分类不能移动" }
            val descendants = descendantIds(categoryId, categories)
            require(newParentId !in descendants) { "不能移动到自身或子分类下" }
            val parent = newParentId?.let { id -> categories.firstOrNull { it.id == id } ?: error("目标分类不存在") }
            require(parent == null || parent.type == source.type) { "只能在同一收支类型中移动" }
            require(categories.none { it.id != source.id && it.parentId == newParentId && it.name == source.name }) {
                "目标下存在同名分类，请使用合并"
            }
            var additionallyMoved = rehomeDirectTransactionsToUnspecified(source.id, System.currentTimeMillis())
            if (newParentId != null) additionallyMoved += rehomeDirectTransactionsToUnspecified(newParentId, System.currentTimeMillis())
            dao.updateCategory(source.copy(parentId = newParentId, updatedAtMs = System.currentTimeMillis()))
            val rewritten = rewriteCategoryPaths(descendants)
            result = rewritten.copy(movedTransactions = rewritten.movedTransactions + additionallyMoved)
        }
        return result
    }

    fun mergeCategories(sourceId: String, targetId: String): CategoryMutationResult {
        ensureCategoryTree()
        var result = CategoryMutationResult(0)
        db.runInTransaction {
            val dao = db.dao()
            val categories = dao.listCategories()
            val source = categories.firstOrNull { it.id == sourceId } ?: error("来源分类不存在")
            val target = categories.firstOrNull { it.id == targetId } ?: error("目标分类不存在")
            require(source.id != target.id && source.type == target.type) { "只能合并同一类型的不同分类" }
            require(!source.isSystem) { "系统分类不能被合并" }
            require(targetId !in descendantIds(sourceId, categories)) { "不能合并到自己的子分类" }
            val now = System.currentTimeMillis()
            rehomeDirectTransactionsToUnspecified(targetId, now)
            rehomeDirectTransactionsToUnspecified(sourceId, now)
            val refreshed = dao.listCategories()
            val directTarget = refreshed.firstOrNull { it.parentId == targetId && it.name == UNSPECIFIED_NAME }
                ?: refreshed.first { it.id == targetId }
            val directRows = dao.transactionsInCategories(listOf(sourceId))
            directRows.forEachIndexed { index, row -> enqueueCategoryChange(row, directTarget, now + index) }
            val structural = dao.listCategories()
            val targetChildren = structural.filter { it.parentId == targetId }.associateBy { it.name }
            structural.filter { it.parentId == sourceId }.forEach { child ->
                val collision = targetChildren[child.name]
                if (collision == null) dao.updateCategory(child.copy(parentId = targetId, updatedAtMs = now))
                else mergeCategoryNodes(child.id, collision.id)
            }
            dao.deleteCategories(listOf(sourceId))
            val affected = descendantIds(targetId, dao.listCategories())
            val rewritten = rewriteCategoryPaths(affected)
            result = CategoryMutationResult(directRows.size + rewritten.movedTransactions, 1 + rewritten.removedCategories)
        }
        return result
    }

    fun deleteCategory(categoryId: String): CategoryMutationResult {
        ensureCategoryTree()
        var result = CategoryMutationResult(0)
        db.runInTransaction {
            val dao = db.dao()
            val categories = dao.listCategories()
            val source = categories.firstOrNull { it.id == categoryId } ?: error("分类不存在")
            require(!source.isSystem) { "系统分类不能删除" }
            val ids = descendantIds(categoryId, categories)
            val uncategorized = categories.first { it.type == source.type && it.isSystem && it.name == UNCATEGORIZED_NAME }
            val rows = dao.transactionsInCategories(ids)
            val now = System.currentTimeMillis()
            rows.forEachIndexed { index, row -> enqueueCategoryChange(row, uncategorized, now + index) }
            val removed = dao.deleteCategories(ids)
            result = CategoryMutationResult(rows.size, removed)
        }
        return result
    }

    fun changeTransactionCategory(transactionId: String, categoryId: String): Boolean {
        ensureCategoryTree()
        var changed = false
        db.runInTransaction {
            val dao = db.dao()
            val categories = dao.listCategories()
            val target = categories.firstOrNull { it.id == categoryId } ?: error("分类不存在")
            require(categories.none { it.parentId == target.id }) { "账单只能绑定到最下级分类" }
            val row = dao.findTransaction(transactionId) ?: return@runInTransaction
            if (row.categoryId != target.id) {
                enqueueCategoryChange(row, target, System.currentTimeMillis())
                changed = true
            }
        }
        return changed
    }

    private fun mergeCategoryNodes(sourceId: String, targetId: String) {
        val dao = db.dao()
        var categories = dao.listCategories()
        require(categories.any { it.id == sourceId }) { "来源分类不存在" }
        val now = System.currentTimeMillis()
        rehomeDirectTransactionsToUnspecified(targetId, now)
        rehomeDirectTransactionsToUnspecified(sourceId, now)
        categories = dao.listCategories()
        val target = categories.firstOrNull { it.parentId == targetId && it.name == UNSPECIFIED_NAME }
            ?: categories.first { it.id == targetId }
        dao.transactionsInCategories(listOf(sourceId)).forEachIndexed { index, row -> enqueueCategoryChange(row, target, now + index) }
        val targetChildren = categories.filter { it.parentId == targetId }.associateBy { it.name }
        categories.filter { it.parentId == sourceId }.forEach { child ->
            targetChildren[child.name]?.let { mergeCategoryNodes(child.id, it.id) }
                ?: dao.updateCategory(child.copy(parentId = targetId, updatedAtMs = now))
        }
        dao.deleteCategories(listOf(sourceId))
    }

    private fun rehomeDirectTransactionsToUnspecified(parentId: String, now: Long): Int {
        val dao = db.dao()
        val directRows = dao.transactionsInCategories(listOf(parentId))
        if (directRows.isEmpty()) return 0
        val categories = dao.listCategories()
        val parent = categories.firstOrNull { it.id == parentId } ?: return 0
        val existing = categories.firstOrNull { it.parentId == parentId && it.name == UNSPECIFIED_NAME }
        val child = existing ?: LedgerCategoryEntity(
            id = UUID.nameUUIDFromBytes("manual-category-unspecified-v1:$parentId".toByteArray()).toString(),
            type = parent.type,
            name = UNSPECIFIED_NAME,
            parentId = parentId,
            sortOrder = Int.MAX_VALUE,
            isSystem = false,
            createdAtMs = now,
            updatedAtMs = now,
        ).also { dao.insertCategoryIgnore(it) }
        directRows.forEachIndexed { index, row -> enqueueCategoryChange(row, child, now + index) }
        return directRows.size
    }

    private fun rewriteCategoryPaths(categoryIds: List<String>): CategoryMutationResult {
        if (categoryIds.isEmpty()) return CategoryMutationResult(0)
        val dao = db.dao()
        val categories = dao.listCategories()
        val byId = categories.associateBy { it.id }
        val rows = dao.transactionsInCategories(categoryIds)
        val now = System.currentTimeMillis()
        rows.forEachIndexed { index, row ->
            val node = byId[row.categoryId] ?: return@forEachIndexed
            enqueueCategoryChange(row, node, now + index, byId)
        }
        return CategoryMutationResult(rows.size)
    }

    private fun enqueueCategoryChange(
        original: ManualTransactionEntity,
        target: LedgerCategoryEntity,
        now: Long,
        categories: Map<String, LedgerCategoryEntity> = db.dao().listCategories().associateBy { it.id },
    ) {
        val updated = original.copy(categoryId = target.id, category = categoryPath(target.id, categories), updatedAtMs = now)
        db.dao().updateTransaction(updated)
        db.dao().insertOutbox(SyncOutboxEntity(
            UUID.randomUUID().toString(), updated.id, ManualLedgerMigrationCodec.transactionJson(updated),
            OutboxState.PENDING, 0, now, now,
        ))
    }

    private fun descendantIds(rootId: String, categories: List<LedgerCategoryEntity>): List<String> {
        if (categories.none { it.id == rootId }) return emptyList()
        val children = categories.groupBy { it.parentId }
        val result = mutableListOf<String>()
        fun visit(id: String) { result += id; children[id].orEmpty().forEach { visit(it.id) } }
        visit(rootId)
        return result
    }

    private fun categoryPath(id: String, categories: Map<String, LedgerCategoryEntity>): String {
        val names = mutableListOf<String>()
        var cursor: LedgerCategoryEntity? = categories[id]
        val visited = mutableSetOf<String>()
        while (cursor != null && visited.add(cursor.id)) {
            names += cursor.name
            cursor = cursor.parentId?.let(categories::get)
        }
        return names.asReversed().joinToString(CategoryCatalog.HIERARCHY_SEPARATOR).take(80)
    }

    private fun categorySegments(path: String): List<String> = path.split(CategoryCatalog.HIERARCHY_SEPARATOR)
        .map(String::trim).filter(String::isNotBlank).ifEmpty { listOf(UNCATEGORIZED_NAME) }

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

    fun update(id: String, input: NewManualTransaction): Boolean {
        val cents = parseCents(input.amountText) ?: error("金额格式错误")
        require(input.category.isNotBlank()) { "分类不能为空" }
        require(input.account.isNotBlank()) { "账户不能为空" }
        val original = db.dao().findTransaction(id) ?: return false
        val now = System.currentTimeMillis()
        val updated = original.copy(
            type = input.type,
            amountCents = cents,
            category = if (CategoryCatalog.HIERARCHY_SEPARATOR in input.category) input.category.trim().take(80)
                else CategoryCatalog.normalize(input.type, input.category),
            account = input.account.trim().take(40),
            targetAccount = input.targetAccount?.trim()?.take(40)?.ifBlank { null },
            occurredAtMs = input.occurredAtMs,
            note = input.note?.trim()?.take(200)?.ifBlank { null },
            updatedAtMs = now,
            categoryId = null,
        )
        val event = SyncOutboxEntity(
            eventId = UUID.randomUUID().toString(),
            transactionId = id,
            payload = ManualLedgerMigrationCodec.transactionJson(updated),
            state = OutboxState.PENDING,
            attempts = 0,
            nextAttemptAtMs = now,
            createdAtMs = now,
        )
        var changed = false
        db.runInTransaction {
            if (db.dao().updateTransaction(updated) > 0) {
                db.dao().insertOutbox(event)
                changed = true
            }
        }
        if (changed) ensureCategoryTree()
        return changed
    }

    companion object {
        const val UNCATEGORIZED_NAME = "无分类"
        const val UNSPECIFIED_NAME = "未细分"

        fun open(context: Context): ManualLedgerRepository {
            val db = Room.databaseBuilder(
                context.applicationContext,
                ManualLedgerDatabase::class.java,
                "manual-ledger.db",
            ).addMigrations(MANUAL_LEDGER_MIGRATION_1_2).build()
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
