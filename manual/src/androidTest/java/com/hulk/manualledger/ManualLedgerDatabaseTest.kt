package com.hulk.manualledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualLedgerDatabaseTest {
    private lateinit var database: ManualLedgerDatabase
    private lateinit var repository: ManualLedgerRepository

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ManualLedgerDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = ManualLedgerRepository(database)
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createDeduplicateEditAndDeleteRemainAtomic() {
        val id = "device-test-stable-id"
        val original = NewManualTransaction(
            stableId = id,
            type = ManualTransactionType.EXPENSE,
            amountText = "12.34",
            category = "餐饮",
            account = "现金",
            occurredAtMs = 1_700_000_000_000,
            note = "原记录",
        )
        assertTrue(repository.add(original))
        assertFalse(repository.add(original))
        assertEquals(1, repository.list().size)
        assertEquals(1, repository.pendingSyncCount())

        assertTrue(repository.update(id, original.copy(amountText = "23.45", category = "交通", note = "已修改")))
        val edited = repository.list().single()
        assertEquals(2345, edited.amountCents)
        assertEquals("交通", edited.category)
        assertEquals("已修改", edited.note)
        assertEquals(1, repository.pendingSyncCount())

        repository.delete(id)
        assertTrue(repository.list().isEmpty())
        assertEquals(1, repository.pendingSyncCount())
    }

    @Test
    fun trashRestorePurgeAndCloudReplayPreserveUserIntent() {
        val input = NewManualTransaction(
            stableId = "trash-test", type = ManualTransactionType.EXPENSE,
            amountText = "12.34", category = "餐饮", account = "现金", note = "完整保留",
        )
        repository.add(input)
        val original = repository.list().single()
        repository.delete(original.id)
        assertTrue(repository.list().isEmpty())
        assertEquals(original.copy(deletedAtMs = repository.trash().single().deletedAtMs,
            updatedAtMs = repository.trash().single().updatedAtMs), repository.trash().single())
        assertEquals(1, repository.import(listOf(input.copy(note = "不应覆盖"))).unchanged)
        assertFalse(repository.update(original.id, input))
        assertTrue(repository.restore(original.id))
        val restored = repository.list().single()
        assertEquals(original.amountCents, restored.amountCents)
        assertEquals(original.note, restored.note)
        assertEquals(original.createdAtMs, restored.createdAtMs)
        assertTrue(repository.trash().isEmpty())
        // A confirmation snapshot must never delete a row restored meanwhile.
        assertEquals(0, repository.emptyTrash(listOf(original.id)))
        database.dao().markSynced(database.dao().pendingOutbox(Long.MAX_VALUE).map { it.eventId })
        database.dao().applyRemoteChanges(listOf(
            RemoteLedgerChange(original.id, true, null, restored.updatedAtMs - 1),
            RemoteLedgerChange(original.id, false, original),
        ))
        assertEquals(restored, repository.list().single())
        repository.delete(original.id)
        assertEquals(1, repository.emptyTrash(listOf(original.id)))
        assertTrue(repository.trash().isEmpty())
        assertEquals(null, database.dao().findTransaction(original.id))
        assertEquals(1, repository.pendingSyncCount())
        database.dao().markSynced(database.dao().pendingOutbox(Long.MAX_VALUE).map { it.eventId })
        database.dao().applyRemoteChanges(listOf(RemoteLedgerChange(original.id, false, restored)))
        assertTrue(repository.list().isEmpty())
        assertFalse(repository.restore(original.id))
    }

    @Test
    fun trashAndPurgeSurviveDatabaseReopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "recycle-bin-${java.util.UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, ManualLedgerDatabase::class.java, name).allowMainThreadQueries().build()
        var disk = open()
        try {
            var repo = ManualLedgerRepository(disk)
            repo.add(NewManualTransaction(stableId = "durable", type = ManualTransactionType.EXPENSE,
                amountText = "8", category = "餐饮", account = "现金"))
            repo.delete("durable")
            disk.close()
            disk = open()
            repo = ManualLedgerRepository(disk)
            assertTrue(repo.list().isEmpty())
            assertEquals("durable", repo.trash().single().id)
            assertTrue(repo.restore("durable"))
            repo.delete("durable")
            assertEquals(1, repo.emptyTrash(listOf("durable")))
            disk.close()
            disk = open()
            repo = ManualLedgerRepository(disk)
            assertTrue(repo.list().isEmpty())
            assertTrue(repo.trash().isEmpty())
            assertEquals(1, disk.dao().isPurged("durable"))
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun remoteDeleteMovesExistingRecordToTrashAndNewerRestoreReactivatesIt() {
        repository.add(NewManualTransaction(stableId = "remote-trash", type = ManualTransactionType.INCOME,
            amountText = "100", category = "工资", account = "银行卡"))
        val row = repository.list().single()
        database.dao().markSynced(database.dao().pendingOutbox(Long.MAX_VALUE).map { it.eventId })
        database.dao().applyRemoteChanges(listOf(RemoteLedgerChange(row.id, true, null, row.updatedAtMs + 1)))
        assertTrue(repository.list().isEmpty())
        assertEquals(row.note, repository.trash().single().note)
        database.dao().applyRemoteChanges(listOf(RemoteLedgerChange(row.id, false, row.copy(updatedAtMs = row.updatedAtMs + 2))))
        assertEquals(1, repository.list().size)
        assertTrue(repository.trash().isEmpty())
    }

    @Test
    fun noteEditsAndRenamesPreserveCategoryIdentityAndFinancialFields() {
        repository.import(listOf(NewManualTransaction(stableId = "stable-category", type = ManualTransactionType.EXPENSE,
            amountText = "15", category = "饮食 › 早餐", account = "现金", preserveCategoryPath = true)))
        val breakfast = repository.categories().first { it.name == "早餐" }
        repository.moveCategory(breakfast.id, null)
        val before = repository.list().single()
        assertTrue(repository.update(before.id, NewManualTransaction(type = before.type, amountText = "15", category = "早餐",
            categoryId = breakfast.id, account = before.account, occurredAtMs = before.occurredAtMs, note = "只改备注")))
        assertEquals("早餐", repository.list().single().category)
        assertEquals(breakfast.id, repository.list().single().categoryId)
        repository.renameCategory(breakfast.id, "早饭")
        val renamed = repository.list().single()
        assertEquals("早饭", renamed.category)
        assertEquals(breakfast.id, renamed.categoryId)
        assertEquals(before.amountCents, renamed.amountCents)
        assertEquals(before.occurredAtMs, renamed.occurredAtMs)
        val shopping = repository.categories().first { it.name == "购物" && it.parentId == null }
        repository.moveCategory(breakfast.id, shopping.id)
        assertEquals("购物", CategoryTree.rootName(repository.list().single(), repository.categories()))
        assertEquals("购物 › 早饭", repository.list().single().category)
        repository.deleteCategory(breakfast.id)
        assertFalse(CategoryTree.selectable(before.type, repository.categories()).any { it.id == breakfast.id })
    }

    @Test
    fun singlePurgeLeavesOtherTrashAndActiveRowsUntouched() {
        listOf("purge-a", "purge-b", "active").forEach {
            repository.add(NewManualTransaction(stableId = it, type = ManualTransactionType.EXPENSE, amountText = "8", category = "餐饮", account = "现金"))
        }
        repository.delete("purge-a"); repository.delete("purge-b")
        assertEquals(1, repository.emptyTrash(listOf("purge-a")))
        assertEquals(listOf("purge-b"), repository.trash().map { it.id })
        assertEquals(listOf("active"), repository.list().map { it.id })
        assertEquals(null, database.dao().findTransaction("purge-a"))
    }

    @Test
    fun reimportEnrichesExistingHierarchyWithoutCreatingDuplicateTransaction() {
        val base = NewManualTransaction(
            stableId = "suishou-stable-id",
            type = ManualTransactionType.EXPENSE,
            amountText = "25.60",
            category = "早餐",
            account = "微信",
            occurredAtMs = 1_700_000_000_000,
            preserveCategoryPath = true,
        )
        val first = repository.import(listOf(base))
        assertEquals(ImportStats(inserted = 1, enriched = 0, unchanged = 0), first)

        val enriched = repository.import(listOf(base.copy(category = "食品酒水 › 早餐")))
        assertEquals(ImportStats(inserted = 0, enriched = 1, unchanged = 0), enriched)
        assertEquals(1, repository.list().size)
        assertEquals("食品酒水 › 早餐", repository.list().single().category)

        val repeated = repository.import(listOf(base.copy(category = "食品酒水 › 早餐")))
        assertEquals(ImportStats(inserted = 0, enriched = 0, unchanged = 1), repeated)
        assertEquals(1, repository.pendingSyncCount())
    }

    @Test
    fun categoryTreeMoveMergeDeleteAndLeafBindingUpdateHistoricalTransactionsAtomically() {
        val rows = listOf(
            NewManualTransaction(
                stableId = "tree-a", type = ManualTransactionType.EXPENSE, amountText = "10.00",
                category = "其他 › API", account = "现金", preserveCategoryPath = true,
            ),
            NewManualTransaction(
                stableId = "tree-b", type = ManualTransactionType.EXPENSE, amountText = "20.00",
                category = "其他 › 租服务器", account = "现金", preserveCategoryPath = true,
            ),
        )
        repository.import(rows)
        repository.ensureCategoryTree()
        fun category(name: String, parentName: String? = null): LedgerCategoryEntity {
            val all = repository.categories()
            return all.first { node ->
                node.name == name && (parentName == null && node.parentId == null ||
                    parentName != null && all.firstOrNull { it.id == node.parentId }?.name == parentName)
            }
        }

        val other = category("其他")
        val api = category("API", "其他")
        val server = category("租服务器", "其他")
        assertTrue(repository.categoryImpact(other.id).categoryCount >= 3)
        assertEquals(2, repository.categoryImpact(other.id).transactionCount)
        assertLeafBindings()
        assertEquals(2, repository.pendingSyncCount())

        val moved = repository.moveCategory(server.id, api.id)
        assertEquals(2, moved.movedTransactions)
        assertEquals("其他 › API › 未细分", repository.list().first { it.id == "tree-a" }.category)
        assertEquals("其他 › API › 租服务器", repository.list().first { it.id == "tree-b" }.category)
        assertLeafBindings()
        assertEquals(2, repository.pendingSyncCount())
        assertEquals(CategoryMutationResult(0), repository.moveCategory(server.id, api.id))
        assertEquals(2, repository.pendingSyncCount())
        assertThrows(IllegalArgumentException::class.java) { repository.moveCategory(api.id, server.id) }
        assertThrows(IllegalArgumentException::class.java) { repository.changeTransactionCategory("tree-a", api.id) }
        assertEquals(2, repository.pendingSyncCount())

        val archive = repository.createCategory(ManualTransactionType.EXPENSE, "归档", other.id)
        assertTrue(repository.changeTransactionCategory("tree-a", archive.id))
        assertEquals("其他 › 归档", repository.list().first { it.id == "tree-a" }.category)
        assertLeafBindings()
        assertEquals(2, repository.pendingSyncCount())

        val merged = repository.mergeCategories(archive.id, server.id)
        assertEquals(2, merged.movedTransactions)
        assertEquals("其他 › API › 租服务器", repository.list().first { it.id == "tree-a" }.category)
        assertEquals(
            repository.list().first { it.id == "tree-b" }.categoryId,
            repository.list().first { it.id == "tree-a" }.categoryId,
        )
        assertLeafBindings()
        assertEquals(2, repository.pendingSyncCount())

        val impact = repository.categoryImpact(api.id)
        assertTrue(impact.categoryCount >= 2)
        assertEquals(2, impact.transactionCount)
        val deleted = repository.deleteCategory(api.id)
        assertEquals(2, deleted.movedTransactions)
        assertTrue(deleted.removedCategories >= 2)
        assertTrue(repository.list().filter { it.id in setOf("tree-a", "tree-b") }.all { it.category == "无分类" })
        assertEquals(2, repository.pendingSyncCount())
        assertLeafBindings()
    }

    @Test
    fun deletedBuiltInCategoryStaysDeletedAndUncategorizedCannotBecomeAParent() {
        repository.ensureCategoryTree()
        val expenseCategories = repository.categories().filter { it.type == ManualTransactionType.EXPENSE }
        val shopping = expenseCategories.first { it.name == "购物" && it.parentId == null }
        val uncategorized = expenseCategories.first { it.isSystem && it.name == "无分类" }
        val child = expenseCategories.first { it.parentId == shopping.id }

        assertThrows(IllegalArgumentException::class.java) {
            repository.moveCategory(child.id, uncategorized.id)
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.createCategory(ManualTransactionType.EXPENSE, "错误子类", uncategorized.id)
        }

        repository.deleteCategory(shopping.id)
        assertTrue(repository.categories().none { it.id == shopping.id })
        assertTrue(repository.categories().none { it.parentId == shopping.id })
    }

    private fun assertLeafBindings() {
        val categories = repository.categories()
        val parentIds = categories.mapNotNull { it.parentId }.toSet()
        assertTrue(repository.list().none { it.categoryId in parentIds })
    }
}
