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
        assertEquals("出行", edited.category)
        assertEquals("已修改", edited.note)
        assertEquals(2, repository.pendingSyncCount())

        repository.delete(id)
        assertTrue(repository.list().isEmpty())
        assertEquals(3, repository.pendingSyncCount())
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
        assertEquals(2, repository.pendingSyncCount())
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

        val moved = repository.moveCategory(server.id, api.id)
        assertTrue(moved.movedTransactions >= 1)
        assertEquals("其他 › API › 未细分", repository.list().first { it.id == "tree-a" }.category)
        assertEquals("其他 › API › 租服务器", repository.list().first { it.id == "tree-b" }.category)
        assertLeafBindings()
        assertThrows(IllegalArgumentException::class.java) { repository.moveCategory(api.id, server.id) }
        assertThrows(IllegalArgumentException::class.java) { repository.changeTransactionCategory("tree-a", api.id) }

        val archive = repository.createCategory(ManualTransactionType.EXPENSE, "归档", other.id)
        assertTrue(repository.changeTransactionCategory("tree-a", archive.id))
        assertEquals("其他 › 归档", repository.list().first { it.id == "tree-a" }.category)
        assertLeafBindings()

        val merged = repository.mergeCategories(archive.id, server.id)
        assertTrue(merged.movedTransactions >= 1)
        assertEquals("其他 › API › 租服务器 › 未细分", repository.list().first { it.id == "tree-a" }.category)
        assertEquals(
            repository.list().first { it.id == "tree-b" }.categoryId,
            repository.list().first { it.id == "tree-a" }.categoryId,
        )
        assertLeafBindings()

        val impact = repository.categoryImpact(api.id)
        assertTrue(impact.categoryCount >= 2)
        assertEquals(2, impact.transactionCount)
        val deleted = repository.deleteCategory(api.id)
        assertEquals(2, deleted.movedTransactions)
        assertTrue(deleted.removedCategories >= 2)
        assertTrue(repository.list().filter { it.id in setOf("tree-a", "tree-b") }.all { it.category == "无分类" })
        assertTrue(repository.pendingSyncCount() >= 7)
        assertLeafBindings()
    }

    private fun assertLeafBindings() {
        val categories = repository.categories()
        val parentIds = categories.mapNotNull { it.parentId }.toSet()
        assertTrue(repository.list().none { it.categoryId in parentIds })
    }
}
