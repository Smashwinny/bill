package com.hulk.manualledger

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
